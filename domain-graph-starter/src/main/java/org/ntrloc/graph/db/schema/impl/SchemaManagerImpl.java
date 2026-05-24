package org.ntrloc.graph.db.schema.impl;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryUpdatedListener;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.ntrloc.graph.DuplicateException;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.LabelConstants;
import org.ntrloc.graph.db.schema.DefinitionWithPropertyGroups;
import org.ntrloc.graph.db.schema.GraphSchemaBackend;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyGroupDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.SchemaChangeReaction;
import org.ntrloc.graph.db.schema.SchemaException;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.ntrloc.graph.db.LabelConstants.ITEM_DEFINITION_LABEL;
import static org.ntrloc.graph.db.LabelConstants.LINK_DEFINITION_LABEL;
import static org.ntrloc.graph.db.LabelConstants.LINK_SOURCE_LABEL;
import static org.ntrloc.graph.db.LabelConstants.LINK_TARGET_LABEL;
import static org.ntrloc.graph.db.LabelConstants.PROPERTY_DEFINITION_LABEL;
import static org.ntrloc.graph.db.PropertyConstants.DESCRIPTION_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.NAME_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.UNIQUE_ID_PROPERTY;

@Service
public class SchemaManagerImpl implements SchemaManager, EntryAddedListener<String, Object>, EntryUpdatedListener<String, Object> {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaManagerImpl.class);

    private static final String SCHEMA_MAP_NAME = "schemaMap";
    private static final String SCHEMA_VERSION_LABEL = "version";
    private static final String HAS_PROPERTY_GROUP = "has-property-group";
    private static final String HAS_PROPERTY = "has-property";

    private final ClusterService clusterService;
    private final GraphSchemaBackend graphSchemaBackend;
    private final GraphTraversalSource traversalSource;

    private IMap<String, Object> schemaMap;
    private List<SchemaChangeReaction> reactions = new ArrayList<>();

    private boolean cachesInitialized = false;
    private Map<String, ItemDefinition> itemDefinitionByNameMap;
    private Map<String, ItemDefinition> itemDefinitionByIdMap;

    public SchemaManagerImpl(GraphSchemaBackend graphSchemaBackend, GraphTraversalSource traversalSource, ClusterService clusterService) {
        this.graphSchemaBackend = graphSchemaBackend;
        this.traversalSource = traversalSource;
        this.schemaMap = clusterService.getMap(SCHEMA_MAP_NAME);
        this.schemaMap.addEntryListener(this, true);

        try {
            this.graphSchemaBackend.ensureGlobalSchema();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted", ie);
            throw new RuntimeException(ie);
        }
        this.clusterService = clusterService;

        cacheSchemaDefinitions();
    }

    private void cacheSchemaDefinitions() {
        itemDefinitionByNameMap = new HashMap<>();
        itemDefinitionByIdMap = new HashMap<>();

        try (var tx = traversalSource.tx()) {
            GraphTraversal<Vertex, Vertex> start = traversalSource.V().hasLabel(ITEM_DEFINITION_LABEL);
            var definitions = retrieveItemDefinitions(start);

            for (ItemDefinition def : definitions) {
                itemDefinitionByNameMap.put(def.getName(), def);
                itemDefinitionByIdMap.put(def.getUid(), def);
            }

            cachesInitialized = true;
            tx.commit();
        }
    }

    private String createNewUniqueId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public ItemDefinition createItemDefinition(ItemDefinition definition) {
        var tx = traversalSource.tx();
        boolean foundVertex = traversalSource.V().hasLabel(ITEM_DEFINITION_LABEL).has("name", definition.getName()).hasNext();
        tx.close();

        if (foundVertex) {
            throw new DuplicateException(String.format("Schema for entity %s already exists", definition.getName()));
        }

        String itemTypeUid = createNewUniqueId();
        definition.setUid(itemTypeUid);

        Set<PropertyDefinition> propertyDefinitions = definition.getProperties() == null ? Set.of() : definition.getProperties();
        Set<PropertyDefinition> groupedPropertyDefinitions = definition.getPropertyGroups() == null ? Set.of() :
                definition.getPropertyGroups().stream().map(PropertyGroupDefinition::getProperties).flatMap(Set::stream).collect(Collectors.toSet());
        Set<PropertyDefinition> allProperties = Stream.concat(propertyDefinitions.stream(), groupedPropertyDefinitions.stream()).collect(Collectors.toSet());

        for (PropertyDefinition propertyDefinition : allProperties) {
            propertyDefinition.setUid(createNewUniqueId());
        }

        Map<String, PropertyType> propertiesByUid = allProperties.stream()
                .collect(Collectors.toMap(PropertyDefinition::getUid, PropertyDefinition::getType));

        try {
            graphSchemaBackend.createItemTypeSchema(itemTypeUid, propertiesByUid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        // create the schema vertices and edges
        tx = traversalSource.tx();
        GraphTraversal<Vertex, ?> traversal = traversalSource.addV(ITEM_DEFINITION_LABEL)
                .property(UNIQUE_ID_PROPERTY, itemTypeUid)
                .property("name", definition.getName())
                .property("description", definition.getDescription())
                .as("schema");

        AtomicInteger vertexCount = new AtomicInteger(0);

        if (definition.getProperties() != null) {
            for (PropertyDefinition p : definition.getProperties()) {
                String ref = String.valueOf(vertexCount.getAndIncrement());

                traversal = traversal.addV(LabelConstants.PROPERTY_DEFINITION_LABEL)
                        .property("name", p.getName())
                        .property("description", p.getDescription())
                        .property("type", p.getType().toString())
                        .property(UNIQUE_ID_PROPERTY, p.getUid())
                        .as(ref);
                traversal = traversal.addE(HAS_PROPERTY)
                        .from("schema").to(ref);
            }
        }

        traversal = appendPropertyGroupTraversal(traversal, definition, vertexCount);

        traversal.iterate();
        tx.commit();
        tx.close();

        var defOpt = retrieveItemDefinition(definition.getName());
        if (defOpt.isPresent()) {
            LOG.info("Created item definition {}", definition);
            signalSchemaChange();
            return defOpt.get();
        } else {
            throw new SchemaException("Definition " + definition.getName() + " not found");
        }
    }

    private GraphTraversal<Vertex, ?> appendPropertyGroupTraversal(GraphTraversal<Vertex, ?> traversal, DefinitionWithPropertyGroups definition, AtomicInteger vertexCount) {
        Set<PropertyGroupDefinition> propertyGroups = definition.getPropertyGroups() == null ? Set.of() : definition.getPropertyGroups();

        for (PropertyGroupDefinition groupDefinition : propertyGroups) {
            String groupRef = String.valueOf(vertexCount.getAndIncrement());

            traversal = traversal.addV(LabelConstants.PROPERTY_GROUP_DEFINITION_LABEL)
                    .property("name", groupDefinition.getName())
                    .property("description", groupDefinition.getDescription())
                    .as(groupRef);
            traversal = traversal.addE(HAS_PROPERTY_GROUP)
                    .from("schema").to(groupRef);

            for (PropertyDefinition groupPropertyDef : groupDefinition.getProperties()) {
                String ref = String.valueOf(vertexCount.getAndIncrement());

                traversal = traversal.addV(LabelConstants.PROPERTY_DEFINITION_LABEL)
                        .property("name", groupPropertyDef.getName())
                        .property("description", groupPropertyDef.getDescription())
                        .property("type", groupPropertyDef.getType().toString())
                        .property(UNIQUE_ID_PROPERTY, groupPropertyDef.getUid())
                        .as(ref);
                traversal = traversal.addE(HAS_PROPERTY)
                        .from(groupRef).to(ref);
            }
        }

        return traversal;
    }

    @Override
    public void updateItemDefinition(ItemDefinition definition) {
        throw new RuntimeException("not done");
    }

    @Override
    public Set<ItemDefinition> retrieveItemDefinitions() {
        if (!cachesInitialized) {
            cacheSchemaDefinitions();
        }
        if (itemDefinitionByIdMap == null) {
            return Set.of();
        } else {
            return new HashSet<>(itemDefinitionByIdMap.values());
        }
    }

    @Override
    public Optional<ItemDefinition> retrieveItemDefinition(String name) {
        GraphTraversal<Vertex, Vertex> start = traversalSource.V().hasLabel(ITEM_DEFINITION_LABEL).has("name", name);
        Set<ItemDefinition> defs = retrieveItemDefinitions(start);
        if (defs.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(defs.iterator().next());
        }
    }

    private Set<ItemDefinition> retrieveItemDefinitions(GraphTraversal<Vertex, Vertex> startingTraversal) {
        var tx = traversalSource.tx();
        try {
            var elementProjectionName = "elements";
            var propertyNodeProjectionName = "propertyNodes";
            var propertyGroupProjectionName = "propertyGroups";

            GraphTraversal<Vertex, Map<String, Object>> iter = startingTraversal
                    .project(elementProjectionName, propertyNodeProjectionName, propertyGroupProjectionName)
                    .by(__.elementMap())
                    .by(__.out(HAS_PROPERTY).elementMap().fold())
                    .by(
                            __.out(HAS_PROPERTY_GROUP)
                                    .project(elementProjectionName, propertyNodeProjectionName)
                                    .by(__.elementMap())
                                    .by(__.out(HAS_PROPERTY).elementMap().fold())
                                    .fold()
                    );

            Set<ItemDefinition> defs = iter.toStream().map(v -> {
                ItemDefinition itemDefinition = new ItemDefinition();
                var propertyMap = (HashMap) v.get(elementProjectionName);
                itemDefinition.setName((String) propertyMap.get("name"));
                itemDefinition.setDescription((String) propertyMap.get("description"));
                itemDefinition.setUid((String) propertyMap.get(UNIQUE_ID_PROPERTY));

                ArrayList<Map<String, Object>> properties = (ArrayList) v.get(propertyNodeProjectionName);
                Set<PropertyDefinition> schemaProps = properties.stream()
                        .map(this::mapToPropertyDefinition).collect(Collectors.toSet());
                itemDefinition.setProperties(schemaProps);

                ArrayList<Map<String, Object>> propertyGroups = (ArrayList) v.get(propertyGroupProjectionName);
                Set<PropertyGroupDefinition> groups = propertyGroups.stream()
                        .map(this::mapToPropertyGroupDefinition)
                        .collect(Collectors.toSet());
                itemDefinition.setPropertyGroups(groups);
                return itemDefinition;
            }).collect(Collectors.toSet());

            TreeSet<ItemDefinition> retSet = new TreeSet<>(Comparator.comparing(ItemDefinition::getName));
            retSet.addAll(defs);
            return retSet;
        } finally {
            tx.close();
        }
    }

    private PropertyDefinition mapToPropertyDefinition(Map<String, Object> map) {
        PropertyDefinition p = new PropertyDefinition();
        p.setName((String) map.get("name"));
        p.setUid((String) map.get(UNIQUE_ID_PROPERTY));
        p.setDescription((String) map.get("description"));
        p.setType(PropertyType.valueOf((String) map.get("type")));
        return p;
    }

    private PropertyGroupDefinition mapToPropertyGroupDefinition(Map<String, Object> map) {
        PropertyGroupDefinition group = new PropertyGroupDefinition();
        Map<String, Object> elements = (Map) map.get("elements");
        ArrayList<Map<String, Object>> propertyNodes = (ArrayList) map.get("propertyNodes");
        group.setName((String) elements.get("name"));
        group.setDescription((String) elements.get("description"));
        Set<PropertyDefinition> groupProperties = propertyNodes.stream()
                .map(this::mapToPropertyDefinition).collect(Collectors.toSet());
        group.setProperties(groupProperties);
        return group;
    }

    @Override
    public void deleteItemDefinition(ItemDefinition definition) {
        throw new RuntimeException("not done");
    }

    @Override
    public PropertyDefinition createItemPropertyDefinition(String itemDefinitionId, PropertyDefinition definition) {
        ItemDefinition itemDefinition = itemDefinitionByIdMap.get(itemDefinitionId);
        String uid = UUID.randomUUID().toString();
        var traversal = traversalSource.addV(LabelConstants.PROPERTY_DEFINITION_LABEL)
                .property("name", definition.getName())
                .property("description", definition.getDescription())
                .property("type", definition.getType().toString())
                .property(UNIQUE_ID_PROPERTY, uid)
                .as("prop")
                .addE(HAS_PROPERTY).from(__.V().has(UNIQUE_ID_PROPERTY, itemDefinition.getUid())).to("prop");
        traversal.iterate();
        traversalSource.tx().commit();

        var newProp = getPropertyDefinition(uid);
        signalSchemaChange();
        return newProp;
    }

    @Override
    public void createLinkDefinition(LinkDefinition definition) {
        var tx = traversalSource.tx();
        boolean foundVertex = traversalSource.V().hasLabel(LINK_DEFINITION_LABEL)
                .has("sourceLabel", definition.getSourceLabel())
                .has("targetLabel", definition.getTargetLabel()).hasNext();

        tx.close();

        if (foundVertex) {
            throw new DuplicateException(String.format("Schema for relationship %s already exists", definition));
        }

        String linkTypeUid = createNewUniqueId();
        definition.setUid(linkTypeUid);

        tx = traversalSource.tx();
        GraphTraversal<Vertex, ?> traversal = traversalSource.addV(LINK_DEFINITION_LABEL)
                .property(UNIQUE_ID_PROPERTY, linkTypeUid)
                .property("description", definition.getDescription())
                .property("sourceLabel", definition.getSourceLabel())
                .property("targetLabel", definition.getTargetLabel())
                .property("targetCardinalityMin", definition.getTargetCardinality().getMin())
                .property("targetCardinalityMax", definition.getTargetCardinality().getMax())
                .property("sourceCardinalityMin", definition.getSourceCardinality().getMin())
                .property("sourceCardinalityMax", definition.getSourceCardinality().getMax())
                .property("sourceVersionAction", definition.getSourceVersionAction().toString())
                .property("targetVersionAction", definition.getTargetVersionAction().toString())
                .property("instanceMaxCardinality", definition.getInstanceMaxCardinality())
                .as("schema");

        AtomicInteger vertexCount = new AtomicInteger(0);

        if (definition.getProperties() != null) {
            for (PropertyDefinition p : definition.getProperties()) {
                String ref = String.valueOf(vertexCount.getAndIncrement());
                p.setUid(createNewUniqueId());

                traversal = traversal.addV(LabelConstants.PROPERTY_DEFINITION_LABEL)
                        .property(NAME_PROPERTY, p.getName())
                        .property("description", p.getDescription())
                        .property("type", p.getType().toString())
                        .property(UNIQUE_ID_PROPERTY, p.getUid())
                        .as(ref);
                traversal = traversal.addE(HAS_PROPERTY)
                        .from("schema").to(ref);
            }
        }

        traversal = appendPropertyGroupTraversal(traversal, definition, vertexCount);

        traversal = traversal.V().hasLabel(ITEM_DEFINITION_LABEL).has(NAME_PROPERTY, definition.getSourceItemType()).as("source")
                .V().hasLabel(ITEM_DEFINITION_LABEL).has(NAME_PROPERTY, definition.getTargetItemType()).as("target")
                .addE(LINK_SOURCE_LABEL).from("schema").to("source")
                .addE(LINK_TARGET_LABEL).from("schema").to("target");

        traversal.iterate();
        tx.commit();
        tx.close();

        LOG.info("Created definition {}", definition);
        signalSchemaChange();
    }

    @Override
    public void updateLinkDefinition(LinkDefinition definition) {
        throw new RuntimeException("not done");
    }

    @Override
    public PropertyDefinition updatePropertyDefinition(String propertyUid, String name, String description) {
        var updateTrav = traversalSource.V().hasLabel(PROPERTY_DEFINITION_LABEL).has(UNIQUE_ID_PROPERTY, propertyUid)
                .property(NAME_PROPERTY, name)
                .property(DESCRIPTION_PROPERTY, description);
        if (updateTrav.hasNext()) {
            updateTrav.next();
        } else {
            throw new SchemaException(String.format("Property %s not found", propertyUid));
        }
        traversalSource.tx().commit();

        var updatedProp = getPropertyDefinition(propertyUid);
        signalSchemaChange();
        return updatedProp;
    }

    private PropertyDefinition getPropertyDefinition(String propertyUid) {
        var propTraversal = traversalSource.V()
                .hasLabel(PROPERTY_DEFINITION_LABEL).has(UNIQUE_ID_PROPERTY, propertyUid)
                .elementMap()
                .map(elem -> {
                    var elementMap = elem.get();
                    var transMap = elementMap.keySet().stream().filter(String.class::isInstance).collect(Collectors.toMap(k -> (String) k, k -> elementMap.get(k)));
                    return mapToPropertyDefinition(transMap);
                });
        if (propTraversal.hasNext()) {
            return propTraversal.next();
        } else {
            throw new SchemaException(String.format("Property %s not found", propertyUid));
        }
    }

    @Override
    public Set<LinkDefinition> retrieveLinkDefinitions() {
        GraphTraversal<Vertex, Vertex> start = traversalSource.V().hasLabel(LINK_DEFINITION_LABEL);
        return retrieveRelationshipDefinitions(start);
    }

    private Set<LinkDefinition> retrieveRelationshipDefinitions(GraphTraversal<Vertex, Vertex> startingTraversal) {
        try (var tx = traversalSource.tx()) {
            var elementProjectionName = "elements";
            var propertyNodeProjectionName = "propertyNodes";
            var propertyGroupProjectionName = "propertyGroups";
            var sourceProjectionName = "source";
            var targetProjectionName = "target";

            GraphTraversal<Vertex, Map<String, Object>> iter = startingTraversal
                    .project(elementProjectionName, propertyNodeProjectionName, propertyGroupProjectionName, sourceProjectionName, targetProjectionName)
                    .by(__.elementMap())
                    .by(__.out(HAS_PROPERTY).elementMap().fold())
                    .by(
                            __.out(HAS_PROPERTY_GROUP)
                                    .project(elementProjectionName, propertyNodeProjectionName)
                                    .by(__.elementMap())
                                    .by(__.out(HAS_PROPERTY).elementMap().fold())
                                    .fold()
                    )
                    .by(__.out(LINK_SOURCE_LABEL).values("name"))
                    .by(__.out(LINK_TARGET_LABEL).values("name"));

            return iter.toStream().map(v -> {
                LinkDefinition schema = new LinkDefinition();
                var propertyMap = (HashMap) v.get(elementProjectionName);
                schema.setUid((String) propertyMap.get(UNIQUE_ID_PROPERTY));
                schema.setDescription((String) propertyMap.get("description"));
                schema.setSourceItemType((String) v.get(sourceProjectionName));
                schema.setTargetItemType((String) v.get(targetProjectionName));
                schema.setSourceLabel((String) propertyMap.get("sourceLabel"));
                schema.setTargetLabel((String) propertyMap.get("targetLabel"));
                schema.setSourceCardinality(new org.ntrloc.graph.db.schema.Cardinality((Integer) propertyMap.get("sourceCardinalityMin"), (Integer) propertyMap.get("sourceCardinalityMax")));
                schema.setTargetCardinality(new org.ntrloc.graph.db.schema.Cardinality((Integer) propertyMap.get("targetCardinalityMin"), (Integer) propertyMap.get("targetCardinalityMax")));
                schema.setInstanceMaxCardinality((Integer) propertyMap.get("instanceMaxCardinality"));
                schema.setSourceVersionAction(LinkDefinition.VersionAction.valueOf((String) propertyMap.get("sourceVersionAction")));
                schema.setTargetVersionAction(LinkDefinition.VersionAction.valueOf((String) propertyMap.get("targetVersionAction")));

                ArrayList<Map<String, Object>> properties = (ArrayList) v.get(propertyNodeProjectionName);
                Set<PropertyDefinition> schemaProps = properties.stream()
                        .map(this::mapToPropertyDefinition).collect(Collectors.toSet());
                schema.setProperties(schemaProps);

                ArrayList<Map<String, Object>> propertyGroups = (ArrayList) v.get(propertyGroupProjectionName);
                Set<PropertyGroupDefinition> groups = propertyGroups.stream()
                        .map(this::mapToPropertyGroupDefinition)
                        .collect(Collectors.toSet());
                schema.setPropertyGroups(groups);
                return schema;
            }).collect(Collectors.toSet());
        }
    }

    @Override
    public Optional<LinkDefinition> retrieveLinkDefinition(String linkId) {
        GraphTraversal<Vertex, Vertex> start = traversalSource.V().hasLabel(LINK_DEFINITION_LABEL).has(UNIQUE_ID_PROPERTY, linkId);
        Set<LinkDefinition> defs = retrieveRelationshipDefinitions(start);
        if (defs.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(defs.iterator().next());
        }
    }

    @Override
    public void deleteLinkDefinition(LinkDefinition definition) {
        throw new RuntimeException("not done");
    }

    public void signalSchemaChange() {
        LOG.info("Signaling schema change");
        cacheSchemaDefinitions();
        reactions.forEach(SchemaChangeReaction::onSchemaChange);
        String uuid = UUID.randomUUID().toString();
        schemaMap.put(SCHEMA_VERSION_LABEL, uuid);
    }

    @Override
    public void addSchemaChangeReaction(SchemaChangeReaction reaction) {
        reactions.add(reaction);
        reaction.onSchemaChange();
    }

    private void entryChanged(EntryEvent<String, Object> entryEvent) {
        LOG.info("got entry event {}", entryEvent);

        if (entryEvent.getKey().equals(SCHEMA_VERSION_LABEL)) {
            var local = clusterService.getLocalMember();
            var eventOrigin = entryEvent.getMember();
            if (!local.equals(eventOrigin)) {
                reactions.forEach(SchemaChangeReaction::onSchemaChange);
            }
        }
    }

    @Override
    public void entryAdded(EntryEvent<String, Object> entryEvent) {
        entryChanged(entryEvent);
    }

    @Override
    public void entryUpdated(EntryEvent<String, Object> entryEvent) {
        entryChanged(entryEvent);
    }
}
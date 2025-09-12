package org.ntrloc.graph.db.schema.impl;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryUpdatedListener;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.Cardinality;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphTransaction;
import org.janusgraph.core.PropertyKey;
import org.janusgraph.core.VertexLabel;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.janusgraph.core.schema.Mapping;
import org.janusgraph.core.schema.SchemaStatus;
import org.janusgraph.graphdb.database.StandardJanusGraph;
import org.janusgraph.graphdb.database.management.GraphIndexStatusReport;
import org.janusgraph.graphdb.database.management.ManagementSystem;
import org.ntrloc.graph.DuplicateException;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.LabelConstants;
import org.ntrloc.graph.db.PropertyConstants;
import org.ntrloc.graph.db.PropertyNameTranslator;
import org.ntrloc.graph.db.schema.DefinitionWithPropertyGroups;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyGroupDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.SchemaChangeReaction;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.ntrloc.graph.db.LabelConstants.ENTITY_DEFINITION_LABEL;
import static org.ntrloc.graph.db.LabelConstants.RELATIONSHIP_DEFINITION_LABEL;

@Service
public class SchemaManagerImpl implements SchemaManager, EntryAddedListener<String, Object>, EntryUpdatedListener<String, Object> {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaManagerImpl.class);

    private static final String SCHEMA_MAP_NAME = "schemaMap";
    private static final String SCHEMA_VERSION_LABEL = "version";
    private final ClusterService clusterService;

    private IMap<String, Object> schemaMap;
    private List<SchemaChangeReaction> reactions = new ArrayList<>();

    private final JanusGraph janusGraph;

    private final GraphTraversalSource traversalSource;

    // TODO: we don't need the traversal source in the constructor if we're getting the graph
    public SchemaManagerImpl(JanusGraph graph, GraphTraversalSource traversalSource, ClusterService clusterService) {
        this.janusGraph = graph;
        this.traversalSource = traversalSource;
        this.schemaMap = clusterService.getMap(SCHEMA_MAP_NAME);
        this.schemaMap.addEntryListener(this, true);
        try {
            this.verifyGlobalPropertiesAndIndexes();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted", ie);
            throw new RuntimeException(ie);
        }
        this.clusterService = clusterService;
    }

    public void verifyGlobalPropertiesAndIndexes() throws InterruptedException {
        JanusGraphManagement management = janusGraph.openManagement();

        PropertyKey statusKey = management.getPropertyKey(PropertyConstants.STATUS_PROPERTY);
        if (statusKey == null) {
            statusKey = management.makePropertyKey(PropertyConstants.STATUS_PROPERTY).dataType(String.class).make();
        }
        PropertyKey transactionKey = management.getPropertyKey(PropertyConstants.TRANSACTION_ID_PROPERTY);
        if (transactionKey == null) {
            transactionKey = management.makePropertyKey(PropertyConstants.TRANSACTION_ID_PROPERTY).dataType(String.class).make();
        }

        String globalIndexName = "GLOBAL";
        if (management.getGraphIndex(globalIndexName) == null) {
            JanusGraphManagement.IndexBuilder builder = management.buildIndex(globalIndexName, Vertex.class);
            builder.addKey(statusKey, Mapping.STRING.asParameter());
            builder.addKey(transactionKey, Mapping.STRING.asParameter());
            builder.buildMixedIndex("search");
            management.commit();

            management = janusGraph.openManagement();
            GraphIndexStatusReport report = ManagementSystem.awaitGraphIndexStatus(janusGraph, globalIndexName).status(SchemaStatus.ENABLED).call();
            LOG.info("Initial index status: {}", report);
        }

        management.commit();
    }

    @Override
    public void createItemDefinition(ItemDefinition definition) {

        StandardJanusGraph standard = (StandardJanusGraph) janusGraph;
        Set<? extends JanusGraphTransaction> transactions = standard.getOpenTransactions();
        LOG.info("Got open transactions: {}", transactions);
        for (JanusGraphTransaction transaction : transactions) {
            transaction.rollback();
            transaction.close();
        }

        var tx = traversalSource.tx();
        boolean foundVertex = traversalSource.V().hasLabel(ENTITY_DEFINITION_LABEL).has("name", definition.getName()).hasNext();
        tx.close();

        if (foundVertex) {
            throw new DuplicateException(String.format("Schema for entity %s already exists", definition.getName()));
        }

        // create the vertex label
        janusGraph.tx().begin();
        VertexLabel typeLabel = janusGraph.getVertexLabel(definition.getName());
        if (typeLabel == null) {
            typeLabel = janusGraph.makeVertexLabel(definition.getName()).make();
            LOG.info("Created new vertex label {}", typeLabel);
            janusGraph.tx().commit();
        } else {
            janusGraph.tx().rollback();
            throw new DuplicateException(String.format("Vertex label for entity %s already exists", definition.getName()));
        }

        Set<PropertyDefinition> propertyDefinitions = definition.getProperties() == null ? Set.of() : definition.getProperties();
        Set<PropertyDefinition> groupedPropertyDefinitions = definition.getPropertyGroups() == null ? Set.of() : definition.getPropertyGroups().stream().map(PropertyGroupDefinition::getProperties).flatMap(Set::stream).collect(Collectors.toSet());
        Set<PropertyDefinition> allProperties = Stream.concat(propertyDefinitions.stream(), groupedPropertyDefinitions.stream()).collect(Collectors.toSet());

        // create the property keys
        JanusGraphManagement management = janusGraph.openManagement();
        Set<String> propertyKeyNames = new HashSet<>();
        for (PropertyDefinition propertyDefinition : allProperties) {
            String propertyKeyName = PropertyNameTranslator.externalPropertyNameToInternalName(definition.getName(), propertyDefinition.getName());

            Class keyClass = switch (propertyDefinition.getType()) {
                case STRING, STRING_LIST -> String.class;
                case INT, INT_LIST -> Integer.class;
                case DATE, DATE_LIST -> Date.class;
                case BOOLEAN, BOOLEAN_LIST -> Boolean.class;
                case DOUBLE, DOUBLE_LIST -> Double.class;
                case BINARY -> null;
            };

            Cardinality cardinality = switch (propertyDefinition.getType()) {
                case STRING, INT, DATE, BOOLEAN, DOUBLE -> Cardinality.SINGLE;
                case STRING_LIST, INT_LIST, DATE_LIST, BOOLEAN_LIST, DOUBLE_LIST -> Cardinality.LIST;
                case BINARY -> null;
            };

            if (keyClass != null || cardinality != null) {
                management.makePropertyKey(propertyKeyName).dataType(keyClass).cardinality(cardinality).make();
                propertyKeyNames.add(propertyKeyName);
            }
        }

        // create the entity index
        VertexLabel entityTypeLabel = management.getVertexLabel(definition.getName());
        Set<PropertyKey> keys = propertyKeyNames.stream().map(management::getPropertyKey).collect(Collectors.toSet());
        JanusGraphManagement.IndexBuilder builder = management.buildIndex(definition.getName(), Vertex.class);
        for (PropertyKey propertyKey : keys) {
            if (propertyKey.dataType() == String.class) {
                builder = builder.addKey(propertyKey, Mapping.STRING.asParameter());
            } else {
                builder = builder.addKey(propertyKey);
            }
        }
        builder.indexOnly(entityTypeLabel).buildMixedIndex("search");

        management.commit();
        management = janusGraph.openManagement();

        try {
            GraphIndexStatusReport report = ManagementSystem.awaitGraphIndexStatus(janusGraph, definition.getName()).status(SchemaStatus.ENABLED).call();
            LOG.info("Index status: {}", report);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            management.commit();
        }

        // create the schema vertices and edges
        tx = traversalSource.tx();
        GraphTraversal<Vertex, ?> traversal = traversalSource.addV(ENTITY_DEFINITION_LABEL)
                .property("name", definition.getName())
                .property("description", definition.getDescription())
                .as("schema");

        AtomicInteger vertexCount = new AtomicInteger(0);

        // add direct properties
        for (PropertyDefinition p : definition.getProperties()) {
            String ref = String.valueOf(vertexCount.getAndIncrement());

            traversal = traversal.addV(LabelConstants.PROPERTY_DEFINITION_LABEL)
                    .property("name", p.getName())
                    .property("description", p.getDescription())
                    .property("type", p.getType().toString())
                    .as(ref);
            traversal = traversal.addE("has-property")
                    .from("schema").to(ref);
        }

        // add property groups
        traversal = appendPropertyGroupTraversal(traversal, definition, vertexCount);

        traversal.iterate();
        tx.commit();
        tx.close();

        LOG.info("Created definition {}", definition);
        signalSchemaChange();

    }

    private GraphTraversal<Vertex, ?> appendPropertyGroupTraversal(GraphTraversal<Vertex, ?> traversal, DefinitionWithPropertyGroups definition, AtomicInteger vertexCount) {
        Set<PropertyGroupDefinition> propertyGroups = definition.getPropertyGroups() == null ? Set.of() : definition.getPropertyGroups();

        for (PropertyGroupDefinition groupDefinition : propertyGroups) {
            String groupRef = String.valueOf(vertexCount.getAndIncrement());

            traversal = traversal.addV(LabelConstants.PROPERTY_GROUP_DEFINITION_LABEL)
                    .property("name", groupDefinition.getName())
                    .property("description", groupDefinition.getDescription())
                    .as(groupRef);
            traversal = traversal.addE("has-property-group")
                    .from("schema").to(groupRef);

            for (PropertyDefinition groupPropertyDef : groupDefinition.getProperties()) {
                String ref = String.valueOf(vertexCount.getAndIncrement());

                traversal = traversal.addV(LabelConstants.PROPERTY_DEFINITION_LABEL)
                        .property("name", groupPropertyDef.getName())
                        .property("description", groupPropertyDef.getDescription())
                        .property("type", groupPropertyDef.getType().toString())
                        .as(ref);
                traversal = traversal.addE("has-property")
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
        GraphTraversal<Vertex, Vertex> start = traversalSource.V().hasLabel(ENTITY_DEFINITION_LABEL);
        return retrieveEntityDefinitions(start);
    }

    @Override
    public Optional<ItemDefinition> retrieveItemDefinition(String name) {
        GraphTraversal<Vertex, Vertex> start = traversalSource.V().hasLabel(ENTITY_DEFINITION_LABEL).has("name", name);
        Set<ItemDefinition> defs = retrieveEntityDefinitions(start);
        if (defs.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(defs.iterator().next());
        }
    }

    private Set<ItemDefinition> retrieveEntityDefinitions(GraphTraversal<Vertex, Vertex> startingTraversal) {
        var elementProjectionName = "elements";
        var propertyNodeProjectionName = "propertyNodes";
        var propertyGroupProjectionName = "propertyGroups";

        GraphTraversal<Vertex, Map<String, Object>> iter = startingTraversal
                .project(elementProjectionName, propertyNodeProjectionName, propertyGroupProjectionName)
                // elements of the schema node
                .by(__.elementMap())
                // a list of the property nodes tied to the schema node
                .by(__.out("has-property").elementMap().fold())
                // a map of the property groups tied to the schema and the properties in them
                .by(
                        __.out("has-property-group")
                                .project(elementProjectionName, propertyNodeProjectionName)
                                .by(__.elementMap())
                                .by(__.out("has-property").elementMap().fold())
                                .fold()
                );

        return iter.toStream().map(v -> {
            ItemDefinition schema = new ItemDefinition();
            var propertyMap = (HashMap) v.get(elementProjectionName);
            schema.setName((String) propertyMap.get("name"));
            schema.setDescription((String) propertyMap.get("description"));

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

    private PropertyDefinition mapToPropertyDefinition(Map<String, Object> map) {
        PropertyDefinition p = new PropertyDefinition();
        p.setName((String) map.get("name"));
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
    public void createLinkDefinition(LinkDefinition definition) {
        var tx = traversalSource.tx();
        boolean foundVertex = traversalSource.V().hasLabel(RELATIONSHIP_DEFINITION_LABEL).has("name", definition.getName()).hasNext();
        tx.close();

        if (foundVertex) {
            throw new DuplicateException(String.format("Schema for relationship %s already exists", definition.getName()));
        }

        // create the schema vertices and edges
        tx = traversalSource.tx();
        GraphTraversal<Vertex, ?> traversal = traversalSource.addV(RELATIONSHIP_DEFINITION_LABEL)
                .property("name", definition.getName())
                .property("description", definition.getDescription())
                .property("sourceEntity", definition.getSourceEntity())
                .property("targetEntity", definition.getTargetEntity())
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

        // add direct properties
        if (definition.getProperties() != null) {
            for (PropertyDefinition p : definition.getProperties()) {
                String ref = String.valueOf(vertexCount.getAndIncrement());

                traversal = traversal.addV(LabelConstants.PROPERTY_DEFINITION_LABEL)
                        .property("name", p.getName())
                        .property("description", p.getDescription())
                        .property("type", p.getType().toString())
                        .as(ref);
                traversal = traversal.addE("has-property")
                        .from("schema").to(ref);
            }
        }

        // add property groups
        traversal = appendPropertyGroupTraversal(traversal, definition, vertexCount);

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
    public Set<LinkDefinition> retrieveLinkDefinitions() {
        GraphTraversal<Vertex, Vertex> start = traversalSource.V().hasLabel(RELATIONSHIP_DEFINITION_LABEL);
        return retrieveRelationshipDefinitions(start);
    }

    private Set<LinkDefinition> retrieveRelationshipDefinitions(GraphTraversal<Vertex, Vertex> startingTraversal) {
        var elementProjectionName = "elements";
        var propertyNodeProjectionName = "propertyNodes";
        var propertyGroupProjectionName = "propertyGroups";

        GraphTraversal<Vertex, Map<String, Object>> iter = startingTraversal
                .project(elementProjectionName, propertyNodeProjectionName, propertyGroupProjectionName)
                // elements of the schema node
                .by(__.elementMap())
                // a list of the property nodes tied to the schema node
                .by(__.out("has-property").elementMap().fold())
                // a map of the property groups tied to the schema and the properties in them
                .by(
                        __.out("has-property-group")
                                .project(elementProjectionName, propertyNodeProjectionName)
                                .by(__.elementMap())
                                .by(__.out("has-property").elementMap().fold())
                                .fold()
                );

        return iter.toStream().map(v -> {
            LinkDefinition schema = new LinkDefinition();
            var propertyMap = (HashMap) v.get(elementProjectionName);
            schema.setName((String) propertyMap.get("name"));
            schema.setDescription((String) propertyMap.get("description"));
            schema.setSourceEntity((String) propertyMap.get("sourceEntity"));
            schema.setTargetEntity((String) propertyMap.get("targetEntity"));
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

    @Override
    public Optional<LinkDefinition> retrieveLinkDefinition(String name) {
        GraphTraversal<Vertex, Vertex> start = traversalSource.V().hasLabel(RELATIONSHIP_DEFINITION_LABEL).has("name", name);
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

    /* Map listener methods */

    @Override
    public void entryAdded(EntryEvent<String, Object> entryEvent) {
        entryChanged(entryEvent);
    }

    @Override
    public void entryUpdated(EntryEvent<String, Object> entryEvent) {
        entryChanged(entryEvent);
    }
}

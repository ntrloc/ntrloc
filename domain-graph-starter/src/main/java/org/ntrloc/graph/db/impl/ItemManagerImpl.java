package org.ntrloc.graph.db.impl;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.ntrloc.graph.db.ItemManager;
import org.ntrloc.graph.db.language.mutation.ItemCreateMutation;
import org.ntrloc.graph.db.language.mutation.ItemDeleteMutation;
import org.ntrloc.graph.db.language.mutation.ItemMutation;
import org.ntrloc.graph.db.language.mutation.ItemMutationResponse;
import org.ntrloc.graph.db.language.mutation.ItemReference;
import org.ntrloc.graph.db.language.mutation.LinkCreateMutation;
import org.ntrloc.graph.db.language.mutation.LinkMutationResponse;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.language.mutation.MutationResponse;
import org.ntrloc.graph.db.language.mutation.MutationType;
import org.ntrloc.graph.db.language.mutation.ReferenceableItemMutation;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.db.storage.BinaryHash;
import org.ntrloc.graph.db.storage.BinaryStorageAdapter;
import org.ntrloc.graph.db.traversal.mutator.Mutator;
import org.ntrloc.graph.db.traversal.projector.Projector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.ntrloc.graph.db.LabelConstants.DATA_LABEL;

@Service
public class ItemManagerImpl implements ItemManager {

    private static final Logger LOG = LoggerFactory.getLogger(ItemManagerImpl.class);

    private GraphTraversalSource traversalSource;
    private BinaryStorageAdapter binaryStorageAdapter;

    private Mutator mutator;
    private Projector projector;

    Map<String, ItemDefinition> itemDefinitionMap;
    Map<String, LinkDefinition> linkDefinitionMap;

    public ItemManagerImpl(GraphTraversalSource traversalSource, BinaryStorageAdapter binaryStorageAdapter, SchemaManager schemaManager, Mutator mutator, Projector projector) {
        this.traversalSource = traversalSource;
        this.binaryStorageAdapter = binaryStorageAdapter;
        this.mutator = mutator;
        this.projector = projector;
        schemaManager.addSchemaChangeReaction(() -> {
            Set<ItemDefinition> itemDefinitionSet = schemaManager.retrieveItemDefinitions();
            itemDefinitionMap = itemDefinitionSet.stream().collect(Collectors.toMap(ItemDefinition::getName, Function.identity()));
            Set<LinkDefinition> linkDefinitions = schemaManager.retrieveLinkDefinitions();
            linkDefinitionMap = linkDefinitions.stream().collect(Collectors.toMap(LinkDefinition::getName, Function.identity()));
        });
    }

    @Override
    public void resetGraph() {
        LOG.warn("Resetting graph. Dropping all vertices and edges.");
        var transaction = traversalSource.tx();
        transaction.begin();
        traversalSource.V().drop();
        traversalSource.E().drop();
        transaction.commit();
        transaction.close();

        // TODO: resetting the graph should also drop the property keys and indexes
    }

    /* -------------------------------- Binary methods -------------------------------- */

    @Override
    public HashingBinaryDataWriter openWriter() throws IOException {
        return binaryStorageAdapter.openWriter();
    }

    @Override
    public String commitBinary(HashingBinaryDataWriter writer) throws IOException {
        BinaryHash hash = binaryStorageAdapter.close(writer);
        var iterator = traversalSource.V().hasLabel(DATA_LABEL)
                .has("sha256", hash.getSha256Hash())
                .has("md5", hash.getMd5Hash())
                .elementMap();
        if (iterator.hasNext()) {
            return (String) iterator.next().get("uid");
        } else {
            var transaction = traversalSource.tx();
            String uid;
            GraphTraversal<Vertex, Vertex> traversal;
            do {
                uid = UUID.randomUUID().toString();
                traversal = traversalSource.V().hasLabel(DATA_LABEL).has("uid", uid);
            } while (traversal.hasNext());

            traversalSource.addV(DATA_LABEL).property(Map.of(
                    "sha256", hash.getSha256Hash(),
                    "md5", hash.getMd5Hash(),
                    "uid", uid
            )).next();

            transaction.commit();
            return uid;
        }
    }

    @Override
    public void abandonBinary(HashingBinaryDataWriter writer) {
        binaryStorageAdapter.abandon(writer);
    }

    /* -------------------------------- Mutation methods -------------------------------- */

    @Override
    public MutationResponse executeMutation(MutationRequest mutationRequest) {
        LOG.info("Executing mutation {}", mutationRequest);

        MutationResponse response = new MutationResponse();

        List<ItemMutation> itemMutations = mutationRequest.getItemMutations();
        Set<ItemCreateMutation> createMutations = itemMutations.stream().filter(ItemCreateMutation.class::isInstance)
                .map(ItemCreateMutation.class::cast).collect(Collectors.toSet());
        Set<ItemDeleteMutation> deleteMutations = itemMutations.stream().filter(ItemDeleteMutation.class::isInstance)
                .map(ItemDeleteMutation.class::cast).collect(Collectors.toSet());

        /* Used to map item mutations to the ID of the items they mutated */
        Map<String, ReferenceableItemMutation> mutationIdMap = new HashMap<>();

        for (ItemCreateMutation createMutation: createMutations) {
            String createdId = mutator.createNode(createMutation.getEntityType(), createMutation.getProperties());
            mutationIdMap.put(createdId, createMutation);
            ItemMutationResponse item = new ItemMutationResponse(MutationType.CREATE, createMutation.getEntityType(), createdId);
            response.addItemMutationResponse(item);
        }

        for (ItemDeleteMutation deleteMutation: deleteMutations) {
            String deleteId = deleteMutation.getId();
            String itemType = mutator.deleteNode(deleteId);
            ItemMutationResponse item = new ItemMutationResponse(MutationType.DELETE, itemType, deleteId);
            response.addItemMutationResponse(item);
        }

        for (ItemCreateMutation createMutation: createMutations) {
            String fromId = null;
            Optional<Map.Entry<String, ReferenceableItemMutation>> createdEntryOpt = mutationIdMap.entrySet().stream().filter(entry -> entry.getValue() == createMutation).findAny();
            if (createdEntryOpt.isPresent()) {
                fromId = createdEntryOpt.get().getKey();
            } else {
                throw new RuntimeException("Item mutation " + createMutation + " not found");
            }

            for (LinkCreateMutation linkCreate: createMutation.getLinks()) {
                var linkType = linkCreate.getLinkType();
                var linkDefinition = linkDefinitionMap.get(linkType);
                if (linkDefinition == null) {
                    throw new RuntimeException("Link type " + linkType + " not found");
                }
                var reference = linkCreate.getLinkedItemReference();
                String toId = switch(reference.getType()) {
                    case ItemReference.ReferenceType.MUTATION -> {
                        // find the ID of the item whose reference matches this reference ID
                        Optional<Map.Entry<String, ReferenceableItemMutation>> mutationEntryOpt = mutationIdMap.entrySet()
                                .stream()
                                .filter(entry -> entry.getValue().getRefId() != null && entry.getValue().getRefId().equals(reference.getId()))
                                .findAny();
                        if (mutationEntryOpt.isPresent()) {
                            yield mutationEntryOpt.get().getKey();
                        } else {
                            throw new RuntimeException("Linked item " + reference.getId() + " not found");
                        }
                    }
                    case ItemReference.ReferenceType.GRAPH -> reference.getId();
                };
                if (toId == null) {
                    throw new RuntimeException("Linked item " + reference.getId() + " not found");
                } else {
                    LOG.info("Linking item to item {}", toId);
                    String linkId = mutator.createLink(fromId, toId, linkDefinition.getName(), linkCreate.getProperties());
                    LinkMutationResponse link = new LinkMutationResponse(MutationType.CREATE, linkId, fromId, toId, linkDefinition.getName());
                    response.addLinkMutationResponse(link);
                }

            }
        }

        mutator.prepare();
        mutator.commit();

        return response;
    }

    /* -------------------------------- Projection methods -------------------------------- */

    @Override
    public List<ItemProjection> executeProjection(SelectableItemProjectionSpec spec) {
        List<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> transformedProjections = projections.stream().map(this::transformItemProjection).toList();
        return transformedProjections;
    }

    private ItemProjection transformItemProjection(ItemProjection itemProjection) {
        if (itemProjection.getProperties() != null) {
            Map<String, Object> currentProperties = itemProjection.getProperties();
            ItemDefinition definition = itemDefinitionMap.get(itemProjection.getItemType());
            Map<String, Object> transformedProperties = new HashMap<>();
            for (Map.Entry<String, Object> entry: currentProperties.entrySet()) {
                PropertyDefinition propertyDefinition = definition.getPropertyDefinition(entry.getKey());
                String propertyName = entry.getKey();
                List<Object> value = (List)entry.getValue();
                Object transformedValue = switch(propertyDefinition.getType()) {
                    case BOOLEAN, DATE, DOUBLE, INT, STRING -> value.get(0);
                    default -> value;
                };
                transformedProperties.put(propertyName, transformedValue);
            }
            itemProjection.setProperties(transformedProperties);
        }

        // TODO: transform links

        return itemProjection;
    }

}

package org.ntrloc.graph.db.impl;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.ntrloc.graph.db.ItemManager;
import org.ntrloc.graph.db.language.mutation.ItemCreateMutation;
import org.ntrloc.graph.db.language.mutation.ItemDeleteMutation;
import org.ntrloc.graph.db.language.mutation.ItemMutation;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.language.mutation.MutationResponse;
import org.ntrloc.graph.db.language.mutation.MutationResponseItem;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.db.storage.BinaryHash;
import org.ntrloc.graph.db.storage.BinaryStorageAdapter;
import org.ntrloc.graph.db.traversal.mutator.Mutator;
import org.ntrloc.graph.db.traversal.projector.Projector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
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

    Map<String, ItemDefinition> entityDefinitionMap;
    Map<String, LinkDefinition> relationshipDefinitionMap;

    public ItemManagerImpl(GraphTraversalSource traversalSource, BinaryStorageAdapter binaryStorageAdapter, SchemaManager schemaManager, Mutator mutator, Projector projector) {
        this.traversalSource = traversalSource;
        this.binaryStorageAdapter = binaryStorageAdapter;
        this.mutator = mutator;
        this.projector = projector;
        schemaManager.addSchemaChangeReaction(() -> {
            Set<ItemDefinition> itemDefinitionSet = schemaManager.retrieveEntityDefinitions();
            entityDefinitionMap = itemDefinitionSet.stream().collect(Collectors.toMap(ItemDefinition::getName, Function.identity()));
            Set<LinkDefinition> linkDefinitions = schemaManager.retrieveRelationshipDefinitions();
            relationshipDefinitionMap = linkDefinitions.stream().collect(Collectors.toMap(LinkDefinition::getName, Function.identity()));
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

        for (ItemCreateMutation createMutation: createMutations) {
            String createdId = mutator.createNode(createMutation.getEntityType(), createMutation.getProperties());
            MutationResponseItem item = new MutationResponseItem(MutationResponseItem.MutationType.CREATE, createMutation.getEntityType(), createdId);
            response.addItem(item);
        }

        for (ItemDeleteMutation deleteMutation: deleteMutations) {
            String deleteId = deleteMutation.getId();
            String itemType = mutator.deleteNode(deleteId);
            MutationResponseItem item = new MutationResponseItem(MutationResponseItem.MutationType.DELETE, itemType, deleteId);
            response.addItem(item);
        }

        mutator.prepare();
        mutator.commit();

        return response;
    }

    /* -------------------------------- Projection methods -------------------------------- */

    @Override
    public List<ItemProjection> executeProjection(SelectableItemProjectionSpec spec) {
        return projector.project(spec);
    }

}

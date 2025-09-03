package org.ntrloc.graph.db.impl;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.language.mutation.EntityCreateMutation;
import org.ntrloc.graph.db.language.mutation.EntityMutation;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.language.mutation.MutationResponse;
import org.ntrloc.graph.db.language.mutation.MutationResponseItem;
import org.ntrloc.graph.db.language.query.Query;
import org.ntrloc.graph.db.language.query.QueryResult;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.db.storage.BinaryHash;
import org.ntrloc.graph.db.storage.BinaryStorageAdapter;
import org.ntrloc.graph.db.traversal.mutator.Mutator;
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
public class EntityManagerImpl implements EntityManager {

    private static final Logger LOG = LoggerFactory.getLogger(EntityManagerImpl.class);

    private GraphTraversalSource traversalSource;
    private BinaryStorageAdapter binaryStorageAdapter;

    Map<String, EntityDefinition> entityDefinitionMap;
    Map<String, RelationshipDefinition> relationshipDefinitionMap;

    public EntityManagerImpl(GraphTraversalSource traversalSource, BinaryStorageAdapter binaryStorageAdapter, SchemaManager schemaManager) {
        this.traversalSource = traversalSource;
        this.binaryStorageAdapter = binaryStorageAdapter;
        schemaManager.addSchemaChangeReaction(() -> {
            Set<EntityDefinition> entityDefinitionSet = schemaManager.retrieveEntityDefinitions();
            entityDefinitionMap = entityDefinitionSet.stream().collect(Collectors.toMap(EntityDefinition::getName, Function.identity()));
            Set<RelationshipDefinition> relationshipDefinitions = schemaManager.retrieveRelationshipDefinitions();
            relationshipDefinitionMap = relationshipDefinitions.stream().collect(Collectors.toMap(RelationshipDefinition::getName, Function.identity()));
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

        Mutator mutator = new Mutator(traversalSource);

        MutationResponse response = new MutationResponse();

        List<EntityMutation> entityMutations = mutationRequest.getEntityMutations();
        Set<EntityCreateMutation> createMutations = entityMutations.stream().filter(EntityCreateMutation.class::isInstance)
                .map(EntityCreateMutation.class::cast).collect(Collectors.toSet());
        for (EntityCreateMutation createMutation: createMutations) {
            String createdId = mutator.createNode(createMutation.getEntityType(), createMutation.getProperties());
            MutationResponseItem item = new MutationResponseItem(MutationResponseItem.MutationType.CREATE, createMutation.getEntityType(), createdId);
            response.addItem(item);
        }

        mutator.prepare();
        mutator.commit();

        return response;
    }

    /* -------------------------------- Query methods -------------------------------- */

    @Override
    public QueryResult executeQuery(Query query) {
        throw new RuntimeException("Not implemented yet");
    }
}

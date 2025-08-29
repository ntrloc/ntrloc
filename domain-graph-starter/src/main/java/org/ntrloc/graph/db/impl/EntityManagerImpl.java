package org.ntrloc.graph.db.impl;

import com.google.common.collect.Streams;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.EntityStatus;
import org.ntrloc.graph.db.LabelConstants;
import org.ntrloc.graph.db.PropertyConstants;
import org.ntrloc.graph.db.PropertyNameTranslator;
import org.ntrloc.graph.db.Transaction;
import org.ntrloc.graph.db.language.mutation.EntityCreateMutation;
import org.ntrloc.graph.db.language.mutation.EntityDeleteMutation;
import org.ntrloc.graph.db.language.mutation.EntityMutation;
import org.ntrloc.graph.db.language.mutation.EntityReference;
import org.ntrloc.graph.db.language.mutation.EntityUpdateMutation;
import org.ntrloc.graph.db.language.mutation.ListProperty;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.language.mutation.Property;
import org.ntrloc.graph.db.language.mutation.RelationshipCreateMutation;
import org.ntrloc.graph.db.language.mutation.RelationshipDeleteMutation;
import org.ntrloc.graph.db.language.mutation.RelationshipMutation;
import org.ntrloc.graph.db.language.mutation.RelationshipUpdateMutation;
import org.ntrloc.graph.db.language.mutation.ScalarProperty;
import org.ntrloc.graph.db.language.query.Query;
import org.ntrloc.graph.db.language.query.QueryResult;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.db.storage.BinaryHash;
import org.ntrloc.graph.db.storage.BinaryStorageAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.ntrloc.graph.db.LabelConstants.DATA_LABEL;
import static org.ntrloc.graph.db.PropertyConstants.UNIQUE_ID_PROPERTY;

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

    /* -------------------------------- Transaction methods -------------------------------- */

    @Override
    public Transaction executeMutation(MutationRequest mutationRequest) {
        LOG.info("Executing mutation {}", mutationRequest);

        Transaction t = new Transaction(traversalSource, UUID.randomUUID().toString());

        var transaction = traversalSource.tx();
        transaction.begin();

        GraphTraversal<Vertex, Vertex> traversal = null;

        List<EntityCreateMutation> createMutations = new ArrayList<>();
        List<EntityUpdateMutation> updateMutations = new ArrayList<>();
        List<EntityDeleteMutation> deleteMutations = new ArrayList<>();
        for (EntityMutation entityMutation: mutationRequest.getEntityMutations()) {
            switch (entityMutation) {
                case EntityCreateMutation createMutation: createMutations.add(createMutation); break;
                case EntityUpdateMutation updateMutation: updateMutations.add(updateMutation); break;
                case EntityDeleteMutation deleteMutation: deleteMutations.add(deleteMutation); break;
                default: throw new IllegalArgumentException("Unknown entity mutation: " + entityMutation);
            }
        }

        // generates unique IDs for entities
        Set<String> uids = new HashSet<>();
        Supplier<String> generateId = () -> {
            String sourceId;
            do {
                sourceId = UUID.randomUUID().toString();
            } while (uids.contains(sourceId));

            uids.add(sourceId);
            return sourceId;
        };

        // used to map mutation references to unique entity IDs
        HashMap<String, String> referenceToUniqueIdMap = new HashMap<>();

        // creates
        for (EntityCreateMutation createMutation: createMutations) {
            String uid = generateId.get();

            if (createMutation.getRefId() != null) {
                referenceToUniqueIdMap.put(createMutation.getRefId(), uid);
            }

            traversal = createNode(traversal, t, createMutation.getEntityType(), uid, createMutation.getProperties());
        }

        // updates
        List<String> updateIds = updateMutations.stream().map(EntityUpdateMutation::getId).toList();
        Map<String, String> vertexIdToLabelMap = new HashMap<>();
        Iterator<Map<String, Object>> iter = traversalSource.V()
                .has(UNIQUE_ID_PROPERTY, P.within(updateIds))
                .project("id", "label")
                .by(__.values(UNIQUE_ID_PROPERTY))
                .by(__.label());
        while (iter.hasNext()) {
            Map<String, Object> row = iter.next();
            vertexIdToLabelMap.put(row.get("id").toString(), row.get("label").toString());
        }

        for (EntityUpdateMutation updateMutation: updateMutations) {
            String label = vertexIdToLabelMap.get(updateMutation.getId());
            String uid = generateId.get();

            if (updateMutation.getRefId() != null) {
                referenceToUniqueIdMap.put(updateMutation.getRefId(), updateMutation.getId());
            }

            String targetLabel = "target";
            if (traversal == null) {
                traversal = traversalSource.V().has(UNIQUE_ID_PROPERTY, updateMutation.getId()).as(targetLabel);
            } else {
                traversal = traversal.V().has(UNIQUE_ID_PROPERTY, updateMutation.getId()).as(targetLabel);
            }
            traversal = traversal.addV(LabelConstants.REVISION_LABEL)
                    .property(PropertyConstants.TRANSACTION_ID_PROPERTY, t.getId())
                    .property(UNIQUE_ID_PROPERTY, uid)
                    .as(uid);

            Set<String> deletedProperties = new HashSet<>();
            for (Property property : updateMutation.getProperties()) {
                String translatedPropertyName = PropertyNameTranslator.externalPropertyNameToInternalName(label, property.getName());
                if (property instanceof ScalarProperty<?, ?> scalarProperty) {
                    LOG.info("Applying scalar property {}", translatedPropertyName);
                    if (scalarProperty.getValue() == null) {
                        deletedProperties.add(translatedPropertyName);
                    } else {
                        traversal = traversal.property(translatedPropertyName, scalarProperty.getValue());
                    }
                } else if (property instanceof ListProperty<?> listProperty) {
                    LOG.info("Applying list property {}", translatedPropertyName);
                    if (listProperty.getValues() == null || listProperty.getValues().isEmpty()) {
                        deletedProperties.add(translatedPropertyName);
                    } else {
                        for (Object value : listProperty.getValues()) {
                            traversal = traversal.property(VertexProperty.Cardinality.list, translatedPropertyName, value);
                        }
                    }
                } else {
                    throw new IllegalArgumentException("Unsupported property type: " + property.getClass());
                }
            }
            for (String deletedProperty : deletedProperties) {
                traversal = traversal.property(PropertyConstants.DELETED_PROPERTY_NAME, deletedProperty);
            }
            traversal = traversal.addE(LabelConstants.IS_REVISION_OF_LABEL).from(uid).to(targetLabel).outV();
        }

        // deletes


        // relationships
        List<RelationshipCreateMutation> relationshipCreateMutations = new ArrayList<>();
        List<RelationshipUpdateMutation> relationshipUpdateMutations = new ArrayList<>();
        List<RelationshipDeleteMutation> relationshipDeleteMutations = new ArrayList<>();
        for (RelationshipMutation relationshipMutation: mutationRequest.getRelationshipMutations()) {
            switch(relationshipMutation) {
                case RelationshipCreateMutation createMutation: relationshipCreateMutations.add(createMutation); break;
                case RelationshipUpdateMutation updateMutation: relationshipUpdateMutations.add(updateMutation); break;
                case RelationshipDeleteMutation deleteMutation: relationshipDeleteMutations.add(deleteMutation); break;
                default: throw new IllegalArgumentException("Unknown relationship mutation: " + relationshipMutation);
            }
        }

        for (RelationshipCreateMutation createMutation : relationshipCreateMutations) {
            EntityReference source = createMutation.getSource();
            EntityReference target = createMutation.getTarget();

            String sourceId = referenceToUniqueIdMap.get(source.getId());
            String targetId = referenceToUniqueIdMap.get(target.getId());

            var edgeInLabel = String.format("%s-in", createMutation.getRelationshipType());
            var edgeOutLabel = String.format("%s-out", createMutation.getRelationshipType());
            String uid = generateId.get();
            var linkNodeName = "linkNode";

            traversal = createNode(traversal, t, createMutation.getRelationshipType(), uid, createMutation.getProperties())
                    .as(linkNodeName)
                    .addE(edgeInLabel).from(__.V().has(UNIQUE_ID_PROPERTY, sourceId)).to(linkNodeName)
                    .addE(edgeOutLabel).from(linkNodeName).to(__.V().has(UNIQUE_ID_PROPERTY, targetId)).outV();

        }

        while (traversal != null && traversal.hasNext()) {
            LOG.info("Got traversal result {}", traversal.next());
        }

        transaction.commit();
        transaction.close();

        return t;
    }

    /**
     * Creates a new node (vertex) with a type, some properties, a unique ID, etc. that is associated with a given transaction.
     * @param traversal the graph traversal to which this node create operation should be appended
     * @param transaction the transaction being used to create the node
     * @param entityType the node type to use (vertex label)
     * @param uniqueId the unique ID for the new node
     * @param properties the properties to apply to the node
     * @return the traversal to which the node creation steps have been appended
     */
    private GraphTraversal<Vertex, Vertex> createNode(GraphTraversal<Vertex, Vertex> traversal, Transaction transaction, String entityType, String uniqueId, Set<Property> properties) {
        if (traversal == null) {
            traversal = traversalSource.addV(entityType).as(uniqueId);
        } else {
            traversal = traversal.addV(entityType).as(uniqueId);
        }

        traversal = traversal.property(UNIQUE_ID_PROPERTY, uniqueId)
                .property(PropertyConstants.VERSION_PROPERTY, 1)
                .property(PropertyConstants.STATUS_PROPERTY, EntityStatus.UNCOMMITTED.toString())
                .property(PropertyConstants.TRANSACTION_ID_PROPERTY, transaction.getId());
        for (Property property : properties) {
            String translatedPropertyName = PropertyNameTranslator.externalPropertyNameToInternalName(entityType, property.getName());
            if (property instanceof ScalarProperty<?, ?> scalarProperty) {
                LOG.info("Applying scalar property {}", translatedPropertyName);
                traversal = traversal.property(translatedPropertyName, scalarProperty.getValue());
            } else if (property instanceof ListProperty<?> listProperty) {
                LOG.info("Applying list property {}", translatedPropertyName);
                for (Object value : listProperty.getValues()) {
                    traversal = traversal.property(VertexProperty.Cardinality.list, translatedPropertyName, value);
                }
            } else {
                throw new IllegalArgumentException("Unsupported property type: " + property.getClass());
            }
        }
        return traversal;
    }

    @Override
    public void prepare(Transaction transaction) {
        LOG.info("Preparing transaction {}", transaction.getId());
        long now = new Date().getTime();

        var tx = traversalSource.tx();
        tx.begin();

        var revisionIter = traversalSource.V()
                .has(PropertyConstants.TRANSACTION_ID_PROPERTY)
                .hasLabel(LabelConstants.REVISION_LABEL)
                .project("id", "revisionOf", "properties")
                .by(__.id())
                .by(__.out(LabelConstants.IS_REVISION_OF_LABEL).id())
                .by(__.valueMap());

        GraphTraversal<Vertex, Vertex> traversal = null;

        List<Map<String, Object>> revisions = Streams.stream(revisionIter).toList();
        for (Map<String, Object> revision : revisions) {
            Object revisionId = revision.get("id");
            Object entityId = revision.get("revisionOf");
            Map<String, Object> properties = (Map<String, Object>) revision.get("properties");
            List<String> deletedProperties = (List<String>) properties.getOrDefault(PropertyConstants.DELETED_PROPERTY_NAME, new ArrayList<>());
            if (!deletedProperties.isEmpty()) {
                deletedProperties.add(PropertyConstants.DELETED_PROPERTY_NAME);
            }

            LOG.info("Applying revision {}", revision);
            // copy the target entity to a new version
            var currentLabel = String.format("%s-current", entityId);
            var newLabel = String.format("%s-new", entityId);
            var revisionLabel = String.format("%s-revision", revisionId);
            var currentPropsId = String.format("%s-start-props", entityId);
            var revisionPropsId = String.format("%s-revision-props", revisionId);

            if (traversal == null) {
                traversal = traversalSource.V(revisionId).as(revisionLabel);
            } else {
                traversal = traversal.V(revisionId).as(revisionLabel);
            }
            traversal = traversal.V(entityId).as(currentLabel);

            // creates a new vertex with the same label and properties as the current version
            traversal = traversal.addV(__.select(currentLabel).label()).as(newLabel)
                    .sideEffect(
                            __.select(currentLabel).properties().as(currentPropsId).select(newLabel).property(__.select(currentPropsId).key(), __.select(currentPropsId).value())
                    );

            // copy the properties from the revision to the new vertex
            traversal = traversal.sideEffect(
                    __.select(revisionLabel).properties().as(revisionPropsId).select(newLabel).property(__.select(revisionPropsId).key(), __.select(revisionPropsId).value())
            );

            traversal = traversal.property(UNIQUE_ID_PROPERTY, __.select(revisionLabel).values(UNIQUE_ID_PROPERTY));
            traversal = traversal.property(PropertyConstants.VERSION_PROPERTY, __.select(currentLabel).values(PropertyConstants.VERSION_PROPERTY).math("_ + 1"));
            traversal = traversal.property(PropertyConstants.STATUS_PROPERTY, EntityStatus.UNCOMMITTED.toString());
            for (String deletedProperty : deletedProperties) {
                traversal = traversal.property(deletedProperty, null);
            }
            traversal = traversal.addE(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).from(newLabel).to(currentLabel).outV();

            // drop the revision vertex and reposition back at the new vertex
            traversal = traversal.select(revisionLabel).drop().V(entityId);
        }

        while (traversal != null && traversal.hasNext()) {
            LOG.info("Got traversal result {}", traversal.next());
        }

        tx.commit();
        tx.close();

        LOG.info("Prepared transaction {} in {} ms", transaction.getId(), (new Date().getTime() - now) / 1000);
    }

    @Override
    public void commit(Transaction transaction) {
        LOG.info("Committing transaction {}", transaction.getId());
        long now = new Date().getTime();

        var tx = traversalSource.tx();
        tx.begin();

        traversalSource.V()
                .has(PropertyConstants.STATUS_PROPERTY, EntityStatus.UNCOMMITTED.toString())
                .has(PropertyConstants.TRANSACTION_ID_PROPERTY, transaction.getId())
                .property(PropertyConstants.STATUS_PROPERTY, EntityStatus.NORMAL.toString())
                .iterate();

        tx.commit();
        tx.close();
        LOG.info("Committed transaction {} in {} ms", transaction.getId(), (new Date().getTime() - now) / 1000);
    }

    @Override
    public void abort(Transaction transaction) {
        var tx = traversalSource.tx();
        tx.begin();
        traversalSource.V()
                .has(PropertyConstants.TRANSACTION_ID_PROPERTY, transaction.getId())
                .drop()
                .iterate();
        tx.commit();
        tx.close();
    }

    @Override
    public QueryResult executeQuery(Query query) {
        throw new RuntimeException("Not implemented yet");
    }
}

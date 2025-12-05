package org.ntrloc.graph.db.traversal.mutator.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.ntrloc.graph.db.ItemStatus;
import org.ntrloc.graph.db.LabelConstants;
import org.ntrloc.graph.db.MutationException;
import org.ntrloc.graph.db.PropertyConstants;
import org.ntrloc.graph.db.Transaction;
import org.ntrloc.graph.db.language.ListProperty;
import org.ntrloc.graph.db.language.NodeProperty;
import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.ScalarProperty;
import org.ntrloc.graph.db.traversal.mutator.MutationResult;
import org.ntrloc.graph.db.traversal.mutator.Mutator;
import org.ntrloc.graph.db.traversal.mutator.Node;
import org.ntrloc.graph.db.traversal.mutator.Revision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.ntrloc.graph.db.PropertyConstants.COMMIT_ID_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.GLOBAL_COMMIT_ID_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.ITEM_TYPE_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.LINK_TYPE_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.NODE_PROPERTY_NAME_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.STATUS_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.TRANSACTION_ID_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.UNIQUE_ID_PROPERTY;

public class MutatorImpl implements Mutator {

    private static final Logger LOG = LoggerFactory.getLogger(Mutator.class);

    private GraphTraversalSource traversalSource;
    private Transaction transaction;

    public MutatorImpl(GraphTraversalSource traversalSource) {
        this.traversalSource = traversalSource;
        transaction = new Transaction(traversalSource, UUID.randomUUID().toString());
    }

    @Override
    public String createNode(String label, List<? extends Property> properties) {

        var uniqueId = UUID.randomUUID().toString();

        List<NodeProperty> nodeProperties = properties.stream().filter(property -> property instanceof NodeProperty).map(property -> (NodeProperty)property).toList();
        Map<String, Object> nodePropertiesToNodeIdMap = new HashMap<>();
        for (NodeProperty nodeProperty : nodeProperties) {
            var nodeId = traversalSource.V().hasLabel(LabelConstants.DATA_LABEL).has(UNIQUE_ID_PROPERTY, nodeProperty.getNodeId()).id();
            if (nodeId.hasNext()) {
                nodePropertiesToNodeIdMap.put(nodeProperty.getName(), nodeId.next());
            } else {
                throw new MutationException("Data node with ID " + nodeProperty.getNodeId() + " does not exist");
            }
        }

        var traversal = traversalSource
                .addV(label)
                .as(uniqueId)
                .property(UNIQUE_ID_PROPERTY, uniqueId)
                .property(ITEM_TYPE_PROPERTY, label)
                .property(PropertyConstants.VERSION_PROPERTY, 1)
                .property(PropertyConstants.STATUS_PROPERTY, ItemStatus.UNCOMMITTED_CREATE.toString())
                .property(PropertyConstants.TRANSACTION_ID_PROPERTY, transaction.getId());
        for (Property property : properties) {
            String propertyName = property.getName();
            if (property instanceof ScalarProperty<?, ?> scalarProperty) {
                LOG.info("Applying scalar property {}", propertyName);
                traversal = traversal.property(propertyName, scalarProperty.getValue());
            } else if (property instanceof ListProperty<?> listProperty) {
                LOG.info("Applying list property {}", propertyName);
                for (Object value : listProperty.getValues()) {
                    traversal = traversal.property(VertexProperty.Cardinality.list, propertyName, value);
                }
            } else if (property instanceof NodeProperty nodeProperty) {
                LOG.info("Applying node property {}", property.getName());
                traversal = traversal
                        .addE(LabelConstants.NODE_PROPERTY_EDGE_LABEL)
                        .property(NODE_PROPERTY_NAME_PROPERTY, propertyName)
                        .property(PropertyConstants.COPYABLE_LINK_PROPERTY, true)
                        .from(uniqueId).to(__.V(nodePropertiesToNodeIdMap.get(nodeProperty.getName())))
                        .select(uniqueId);
            } else {
                throw new IllegalArgumentException("Unsupported property type: " + property.getClass());
            }
        }
        traversal.iterate();

        LOG.info("Created node {} with properties {}", uniqueId, properties);

        return uniqueId;
    }

    @Override
    public String updateNode(String uniqueId, List<? extends Property> properties) {
        return updateNodeInternal(uniqueId, properties, ITEM_TYPE_PROPERTY);
    }

    @Override
    public String deleteNode(String uniqueId) {

        String label = null;
        var labelTraversal = traversalSource.V().has(UNIQUE_ID_PROPERTY, uniqueId).project("label").by(__.label());
        if (!labelTraversal.hasNext()) {
            throw new IllegalArgumentException("No node with unique ID " + uniqueId + " found");
        } else {
            label = labelTraversal.next().get("label").toString();
        }

        var updateNodeAlias = "updateNode";
        var revisionAlias = "nodeRevision";

        var traversal = traversalSource.V().has(UNIQUE_ID_PROPERTY, uniqueId).as(updateNodeAlias)
                .addV(LabelConstants.REVISION_LABEL).as(revisionAlias)
                .property(PropertyConstants.TRANSACTION_ID_PROPERTY, transaction.getId())
                .property(UNIQUE_ID_PROPERTY, uniqueId)
                .property(PropertyConstants.REVISION_TYPE_PROPERTY, Revision.RevisionType.DELETE.toString());

        traversal = traversal.addE(LabelConstants.IS_REVISION_OF_LABEL).from(revisionAlias).to(updateNodeAlias).outV();
        traversal.iterate();

        return label;
    }

    @Override
    public String createLink(String fromItemId, String toItemId, String relationshipName, List<? extends Property> properties) {
        var uniqueId = UUID.randomUUID().toString();

        var traversal = traversalSource
                .V().has(UNIQUE_ID_PROPERTY, fromItemId).as("fromNode")
                .V().has(UNIQUE_ID_PROPERTY, toItemId).as("toNode")
                .addV(relationshipName).as("relationship");
        for (Property property : properties) {
            String propertyName = property.getName();
            if (property instanceof ScalarProperty<?, ?> scalarProperty) {
                LOG.info("Applying scalar property {}", propertyName);
                traversal = traversal.property(propertyName, scalarProperty.getValue());
            } else if (property instanceof ListProperty<?> listProperty) {
                LOG.info("Applying list property {}", propertyName);
                for (Object value : listProperty.getValues()) {
                    traversal = traversal.property(VertexProperty.Cardinality.list, propertyName, value);
                }
            }
        }
        traversal = traversal.property(UNIQUE_ID_PROPERTY, uniqueId)
                .property(LINK_TYPE_PROPERTY, relationshipName)
                .property(PropertyConstants.TRANSACTION_ID_PROPERTY, transaction.getId())
                .property(PropertyConstants.STATUS_PROPERTY, ItemStatus.UNCOMMITTED_CREATE.toString())
                .property(PropertyConstants.VERSION_PROPERTY, 1);
        var edgeTraversal = traversal
                        .addE(String.format("%s-in", relationshipName)).property(PropertyConstants.COPYABLE_LINK_PROPERTY, true).from("fromNode").to("relationship")
                        .addE(String.format("%s-out", relationshipName)).property(PropertyConstants.COPYABLE_LINK_PROPERTY, true).from("relationship").to("toNode");
        edgeTraversal.iterate();

        return uniqueId;
    }

    @Override
    public void updateLink(String linkId, List<Property> properties) {
        var linkType = updateNodeInternal(linkId, properties, LINK_TYPE_PROPERTY);
        LOG.info("Updated link {} with properties {}", linkId, properties);
    }

    private String updateNodeInternal(String uniqueId, List<? extends Property> properties, String returnProperty) {
        var updateNodeAlias = "updateNode";
        var revisionAlias = "nodeRevision";

        var traversal = traversalSource.V().has(UNIQUE_ID_PROPERTY, uniqueId).as(updateNodeAlias)
                .addV(LabelConstants.REVISION_LABEL).as(revisionAlias)
                .property(PropertyConstants.TRANSACTION_ID_PROPERTY, transaction.getId())
                .property(UNIQUE_ID_PROPERTY, uniqueId)
                .property(PropertyConstants.REVISION_TYPE_PROPERTY, Revision.RevisionType.UPDATE.toString());

        Set<String> deletedProperties = new HashSet<>();
        for (Property property : properties) {
            String propertyName = property.getName();
            if (property instanceof ScalarProperty<?, ?> scalarProperty) {
                LOG.info("Applying scalar property {}", propertyName);
                if (scalarProperty.getValue() == null) {
                    deletedProperties.add(propertyName);
                } else {
                    traversal = traversal.property(propertyName, scalarProperty.getValue());
                }
            } else if (property instanceof ListProperty<?> listProperty) {
                LOG.info("Applying list property {}", propertyName);
                if (listProperty.getValues() == null || listProperty.getValues().isEmpty()) {
                    deletedProperties.add(propertyName);
                } else {
                    for (Object value : listProperty.getValues()) {
                        traversal = traversal.property(VertexProperty.Cardinality.list, propertyName, value);
                    }
                }
            } else {
                throw new IllegalArgumentException("Unsupported property type: " + property.getClass());
            }
        }
        for (String deletedProperty : deletedProperties) {
            traversal = traversal.property(PropertyConstants.DELETED_PROPERTY_NAME, deletedProperty);
        }

        traversal = traversal.addE(LabelConstants.IS_REVISION_OF_LABEL).from(revisionAlias).to(updateNodeAlias).outV();
        var retTraversal = traversal.select(updateNodeAlias).elementMap(returnProperty).map(trav -> (String)trav.get().get(returnProperty));
        return retTraversal.next();
    }

    /** Generates the next commit ID to apply during a transaction commit. */
    private String getCommitId() {
        // TODO establish a cluster-wide lock before you do this!
        Long retId;
        var idCatalogTraversal = traversalSource.V().hasLabel(LabelConstants.ID_CATALOG_LABEL).values(GLOBAL_COMMIT_ID_PROPERTY);
        if (idCatalogTraversal.hasNext()) {
            retId = (Long)idCatalogTraversal.next();
            traversalSource.V().hasLabel(LabelConstants.ID_CATALOG_LABEL).property(GLOBAL_COMMIT_ID_PROPERTY, retId + 1).iterate();
        } else {
            retId = 0L;
            traversalSource.addV(LabelConstants.ID_CATALOG_LABEL).property(GLOBAL_COMMIT_ID_PROPERTY, retId + 1).iterate();
        }
        return Long.toHexString(retId);
    }

    /* --------------------- Transaction methods --------------------- */

    @Override
    public void begin() {
        transaction.begin();
    }

    @Override
    public String getTransactionId() {
        return transaction.getId();
    }

    @Override
    public void checkpoint() {
        LOG.info("Checkpointing transaction {}", transaction.getId());
        transaction.commit();
    }

    /**
     * Applies all outstanding revisions to nodes and links, creating new UNCOMMITTED node and link versions and removing the revisions.
     */
    @Override
    public void prepare() {
        LOG.info("Preparing transaction {}", transaction.getId());
        long now = new Date().getTime();

        var objectMapper = new ObjectMapper();

        var revisionIter = traversalSource.V()
                .has(PropertyConstants.TRANSACTION_ID_PROPERTY)
                .hasLabel(LabelConstants.REVISION_LABEL)
                .project("id", "label", "revisionOf", "properties")
                .by(__.id())
                .by(__.label())
                .by(__.out(LabelConstants.IS_REVISION_OF_LABEL).project("id", "label", "properties").by(__.id()).by(__.label()).by(__.valueMap()))
                .by(__.valueMap())
                .map(traverser -> {
                    Map<String, Object> proj = traverser.get();
                    Revision revision = objectMapper.convertValue(proj, Revision.class);
                    return revision;
                });

        List<Revision> revisions = revisionIter.toList();

        if (revisions.isEmpty()) {
            LOG.info("No revisions found for transaction {}", transaction.getId());
        } else {
            for (Revision revision : revisions) {
                LOG.info("Applying revision {}", revision);

                var revisionLabel = String.format("%s-revision", revision.getId());
                var currentLabel = String.format("%s-current", revision.getRevisionOf().getId());
                var newLabel = String.format("%s-new", revision.getRevisionOf().getId());

                Node currentNode = revision.getRevisionOf();

                var traversal = traversalSource.V(revision.getId()).as(revisionLabel);

                traversal = traversal.V(currentNode.getId()).as(currentLabel);

                if (revision.getType() == Revision.RevisionType.UPDATE) {
                    Map<Object, Object> appliedProperties = new HashMap<>();

                    // collect all current properties
                    if (currentNode.getProperties() != null) {
                        appliedProperties.putAll(currentNode.getProperties());
                    }

                    // overwrite properties with new values in the revision
                    for (Map.Entry<String, Object> entry : revision.getProperties().entrySet()) {
                        String key = entry.getKey();
                        Object value = entry.getValue();

                        if (key.equals(PropertyConstants.DELETED_PROPERTY_NAME)) {
                            if (value instanceof List deleteList) {
                                for (Object deletedProperty : deleteList) {
                                    appliedProperties.remove(deletedProperty);
                                }
                            } else {
                                appliedProperties.remove(value);
                            }
                        } else {
                            appliedProperties.put(key, value);
                        }
                    }

                    appliedProperties.remove(PropertyConstants.REVISION_TYPE_PROPERTY);

                    var newNodeTraversal = traversal.addV(currentNode.getLabel()).as(newLabel)
                            .property(appliedProperties)
                            .property(PropertyConstants.VERSION_PROPERTY, (int) currentNode.getProperties().getOrDefault(PropertyConstants.VERSION_PROPERTY, 1) + 1)
                            .property(PropertyConstants.STATUS_PROPERTY, ItemStatus.UNCOMMITTED_UPDATE.toString())
                            .addE(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).from(newLabel).to(currentLabel).outV()
                            //.V(revision.getId()).drop()
                            .select(newLabel)
                            .id();
                    Object newNodeId = newNodeTraversal.next();

                    // propagate the edges from the prior version to the new version, excluding edges that are named "system:*".
                    // we don't want to copy links like system:has-previous-version from the old version of the item to the new version
                    // since that would basically link the new version to itself.

                    var currentNodeId = currentNode.getId();

                    traversalSource.V(revision.getId()).drop().iterate();

                    var outEdgeIterator = traversalSource.V(currentNodeId).outE()
                            .has(PropertyConstants.COPYABLE_LINK_PROPERTY, true)
                            .project("edgeLabel", "target")
                            .by(__.label())
                            .by(__.choose(
                                    __.inV().in(LabelConstants.HAS_PREVIOUS_VERSION_LABEL),
                                    __.inV().in(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).id(),
                                    __.inV().id()
                            ));
                    while (outEdgeIterator.hasNext()) {
                        Map<String, Object> edgeProj = outEdgeIterator.next();
                        String edgeLabel = (String)edgeProj.get("edgeLabel");
                        long targetId = (long)edgeProj.get("target");
                        traversalSource.addE(edgeLabel).from(__.V(newNodeId)).to(__.V(targetId)).iterate();
                    }

                    var inEdgeIterator = traversalSource.V(currentNodeId).inE()
                            .has(PropertyConstants.COPYABLE_LINK_PROPERTY, true)
                            .project("edgeLabel", "source")
                            .by(__.label())
                            .by(__.choose(
                                    __.outV().in(LabelConstants.HAS_PREVIOUS_VERSION_LABEL),
                                    __.outV().in(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).id(),
                                    __.outV().id()
                            ));
                    while (inEdgeIterator.hasNext()) {
                        Map<String, Object> edgeProj = inEdgeIterator.next();
                        String edgeLabel = (String)edgeProj.get("edgeLabel");
                        long sourceId = (long)edgeProj.get("source");
                        traversalSource.addE(edgeLabel).from(__.V(sourceId)).to(__.V(newNodeId)).iterate();
                    }


                } else if (revision.getType() == Revision.RevisionType.DELETE) {
                    traversal = traversal.addV(currentNode.getLabel()).as(newLabel)
                            .property(UNIQUE_ID_PROPERTY, currentNode.getProperties().get(UNIQUE_ID_PROPERTY))
                            .property(PropertyConstants.VERSION_PROPERTY, (int) currentNode.getProperties().getOrDefault(PropertyConstants.VERSION_PROPERTY, 1) + 1)
                            .property(PropertyConstants.STATUS_PROPERTY, ItemStatus.UNCOMMITTED_DELETE.toString())
                            .property(PropertyConstants.TRANSACTION_ID_PROPERTY, transaction.getId())
                            .addE(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).from(newLabel).to(currentLabel).outV()
                            .V(revision.getId()).drop()
                            .V(revision.getRevisionOf().getId());
                    traversal.iterate();
                }
            }

            LOG.info("Applied {} node revisions to uncommitted nodes", revisions.size());
        }

        LOG.info("Prepared transaction {} in {} ms", transaction.getId(), (new Date().getTime() - now) / 1000);

        checkpoint();
    }

    /**
     * Commits the transaction and returns the items that were created, updated, or deleted.
     */
    @Override
    public List<MutationResult> commit() {
        LOG.info("Committing transaction {}", transaction.getId());
        long now = new Date().getTime();

        List<MutationResult> results = new ArrayList<>();

        String nodeLabel = "node";
        String priorStatusLabel = "priorStatus";
        String newStatusLabel = "newStatus";

        String commitId = getCommitId();

        var iterator = traversalSource.V()
                .has(STATUS_PROPERTY, P.within(ItemStatus.UNCOMMITTED_CREATE.toString(), ItemStatus.UNCOMMITTED_UPDATE.toString(), ItemStatus.UNCOMMITTED_DELETE.toString()))
                .has(PropertyConstants.TRANSACTION_ID_PROPERTY, transaction.getId())
                .as(nodeLabel)
                .values(STATUS_PROPERTY).as(priorStatusLabel)
                .select(nodeLabel)
                .property(COMMIT_ID_PROPERTY, commitId)
                .property(TRANSACTION_ID_PROPERTY, null)
                .property(STATUS_PROPERTY, __.values(STATUS_PROPERTY).map(v -> {
                    var value = (String)v.get();
                    if (value.equals(ItemStatus.UNCOMMITTED_CREATE.toString())) {
                        return ItemStatus.NORMAL.toString();
                    } else if (value.equals(ItemStatus.UNCOMMITTED_UPDATE.toString())) {
                        return ItemStatus.NORMAL.toString();
                    } else if (value.equals(ItemStatus.UNCOMMITTED_DELETE.toString())) {
                        return ItemStatus.DELETED.toString();
                    } else {
                        throw new IllegalArgumentException("Unexpected status value: " + value);
                    }
                }))
                .project( "uid", priorStatusLabel, newStatusLabel)
                .by(__.values(UNIQUE_ID_PROPERTY))
                .by(__.select(priorStatusLabel))
                .by(__.values(STATUS_PROPERTY));
        while (iterator.hasNext()) {
            Map<String, Object> proj = iterator.next();
            String uid = (String) proj.get("uid");
            String priorStatus = (String) proj.get("priorStatus");
            String newStatus = (String) proj.get("newStatus");
            MutationResult result = new MutationResult(ItemStatus.valueOf(priorStatus), ItemStatus.valueOf(newStatus), uid);
            results.add(result);
        }

        transaction.commit();
        LOG.info("Committed transaction {} in {} ms", transaction.getId(), (new Date().getTime() - now) / 1000);

        return results;
    }

    @Override
    public void abort() {
        throw new RuntimeException("not done");
    }
}

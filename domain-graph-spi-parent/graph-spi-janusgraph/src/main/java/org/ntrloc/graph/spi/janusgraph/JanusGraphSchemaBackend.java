package org.ntrloc.graph.spi.janusgraph;

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
import org.ntrloc.graph.db.PropertyConstants;
import org.ntrloc.graph.db.schema.GraphSchemaBackend;
import org.ntrloc.graph.db.schema.PropertyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class JanusGraphSchemaBackend implements GraphSchemaBackend {

    private static final Logger LOG = LoggerFactory.getLogger(JanusGraphSchemaBackend.class);

    private final JanusGraph janusGraph;

    public JanusGraphSchemaBackend(JanusGraph janusGraph) {
        this.janusGraph = janusGraph;
    }

    @Override
    public void ensureGlobalSchema() throws InterruptedException {
        JanusGraphManagement management = janusGraph.openManagement();

        PropertyKey statusKey = management.getPropertyKey(PropertyConstants.STATUS_PROPERTY);
        if (statusKey == null) {
            statusKey = management.makePropertyKey(PropertyConstants.STATUS_PROPERTY).dataType(String.class).make();
        }
        PropertyKey transactionKey = management.getPropertyKey(PropertyConstants.TRANSACTION_ID_PROPERTY);
        if (transactionKey == null) {
            transactionKey = management.makePropertyKey(PropertyConstants.TRANSACTION_ID_PROPERTY).dataType(String.class).make();
        }
        PropertyKey itemTypeKey = management.getPropertyKey(PropertyConstants.ITEM_TYPE_PROPERTY);
        if (itemTypeKey == null) {
            itemTypeKey = management.makePropertyKey(PropertyConstants.ITEM_TYPE_PROPERTY).dataType(String.class).make();
        }
        PropertyKey uidKey = management.getPropertyKey(PropertyConstants.UNIQUE_ID_PROPERTY);
        if (uidKey == null) {
            uidKey = management.makePropertyKey(PropertyConstants.UNIQUE_ID_PROPERTY).dataType(String.class).make();
        }
        PropertyKey commitIdKey = management.getPropertyKey(PropertyConstants.COMMIT_ID_PROPERTY);
        if (commitIdKey == null) {
            commitIdKey = management.makePropertyKey(PropertyConstants.COMMIT_ID_PROPERTY).dataType(String.class).make();
        }

        String globalIndexName = "GLOBAL";
        if (management.getGraphIndex(globalIndexName) == null) {
            JanusGraphManagement.IndexBuilder builder = management.buildIndex(globalIndexName, Vertex.class);
            builder.addKey(statusKey, Mapping.STRING.asParameter());
            builder.addKey(transactionKey, Mapping.STRING.asParameter());
            builder.addKey(itemTypeKey, Mapping.STRING.asParameter());
            builder.addKey(uidKey, Mapping.STRING.asParameter());
            builder.addKey(commitIdKey, Mapping.STRING.asParameter());
            builder.buildMixedIndex("search");
            management.commit();

            management = janusGraph.openManagement();
            GraphIndexStatusReport report = ManagementSystem.awaitGraphIndexStatus(janusGraph, globalIndexName).status(SchemaStatus.ENABLED).call();
            LOG.info("Initial index status: {}", report);
        } else {
            LOG.info("Global index already exists");
        }

        management.commit();
    }

    @Override
    public void createItemTypeSchema(String itemTypeUid, Map<String, PropertyType> propertiesByUid) throws InterruptedException {
        closeOpenTransactions();

        janusGraph.tx().begin();
        VertexLabel typeLabel = janusGraph.getVertexLabel(itemTypeUid);
        if (typeLabel == null) {
            janusGraph.makeVertexLabel(itemTypeUid).make();
            LOG.info("Created vertex label {} for item type", itemTypeUid);
            janusGraph.tx().commit();
        } else {
            janusGraph.tx().rollback();
            throw new DuplicateException(String.format("Vertex label for entity %s already exists", itemTypeUid));
        }

        JanusGraphManagement management = janusGraph.openManagement();
        Set<String> propertyKeyNames = new HashSet<>();
        for (Map.Entry<String, PropertyType> entry : propertiesByUid.entrySet()) {
            String propertyUid = entry.getKey();
            PropertyType type = entry.getValue();

            Class<?> keyClass = switch (type) {
                case STRING, STRING_LIST, DATE, DATE_LIST -> String.class;
                case INT, INT_LIST -> Integer.class;
                case DATETIME, DATETIME_LIST -> Date.class;
                case BOOLEAN, BOOLEAN_LIST -> Boolean.class;
                case DOUBLE, DOUBLE_LIST -> Double.class;
                case BINARY -> null;
            };

            Cardinality cardinality = switch (type) {
                case STRING, INT, DATE, DATETIME, BOOLEAN, DOUBLE -> Cardinality.SINGLE;
                case STRING_LIST, INT_LIST, DATE_LIST, DATETIME_LIST, BOOLEAN_LIST, DOUBLE_LIST -> Cardinality.LIST;
                case BINARY -> null;
            };

            if (keyClass != null && cardinality != null) {
                management.makePropertyKey(propertyUid).dataType(keyClass).cardinality(cardinality).make();
                propertyKeyNames.add(propertyUid);
            }
        }

        VertexLabel entityTypeLabel = management.getVertexLabel(itemTypeUid);
        Set<PropertyKey> keys = new HashSet<>();
        for (String name : propertyKeyNames) {
            keys.add(management.getPropertyKey(name));
        }

        JanusGraphManagement.IndexBuilder builder = management.buildIndex(itemTypeUid, Vertex.class);
        for (PropertyKey propertyKey : keys) {
            if (propertyKey.dataType() == String.class) {
                builder = builder.addKey(propertyKey, Mapping.STRING.asParameter());
            } else {
                builder = builder.addKey(propertyKey);
            }
            LOG.info("Added key {} to index {}", propertyKey, itemTypeUid);
        }
        builder.indexOnly(entityTypeLabel).buildMixedIndex("search");

        management.commit();
        management = janusGraph.openManagement();
        try {
            GraphIndexStatusReport report = ManagementSystem.awaitGraphIndexStatus(janusGraph, itemTypeUid).status(SchemaStatus.ENABLED).call();
            LOG.info("Index status: {}", report);
        } finally {
            management.commit();
        }
    }

    private void closeOpenTransactions() {
        var standard = (StandardJanusGraph) janusGraph;
        Set<? extends JanusGraphTransaction> transactions = standard.getOpenTransactions();
        LOG.info("Closing {} open transactions before schema change", transactions.size());
        for (JanusGraphTransaction transaction : transactions) {
            transaction.rollback();
            transaction.close();
        }
    }
}

package org.ntrloc.graph.db;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Transaction {

    private static final Logger LOG = LoggerFactory.getLogger(Transaction.class);

    private String id;
    private final GraphTraversalSource source;
    private org.apache.tinkerpop.gremlin.structure.Transaction gremlinTransaction;

    public Transaction(GraphTraversalSource source, String id) {
        this.id = id;
        this.source = source;
        this.gremlinTransaction = source.tx();
    }

    public String getId() {
        return id;
    }

    public org.apache.tinkerpop.gremlin.structure.Transaction getGremlinTransaction() {
        return gremlinTransaction;
    }

    public void begin() {
        if (!gremlinTransaction.isOpen()) {
            gremlinTransaction = source.tx();
            gremlinTransaction.begin();
        }
    }

    public void commit() {
        LOG.info("Committing transaction {}", id);
        gremlinTransaction.commit();
        gremlinTransaction.close();
    }

    public void rollback() {
        LOG.info("Rolling back transaction {}", id);
        gremlinTransaction.rollback();
        gremlinTransaction.close();
    }

    public void close() {
        gremlinTransaction.close();
    }

}

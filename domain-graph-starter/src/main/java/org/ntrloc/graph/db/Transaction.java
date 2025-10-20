package org.ntrloc.graph.db;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Transaction {

    private static final Logger LOG = LoggerFactory.getLogger(Transaction.class);

    private String id;
    private org.apache.tinkerpop.gremlin.structure.Transaction gremlinTransaction;

    public Transaction(GraphTraversalSource source, String id) {
        this.id = id;
        this.gremlinTransaction = source.tx();
    }

    public String getId() {
        return id;
    }

    public org.apache.tinkerpop.gremlin.structure.Transaction getGremlinTransaction() {
        return gremlinTransaction;
    }

    public void begin() {
        if (gremlinTransaction.isOpen()) {
           gremlinTransaction.rollback();
           gremlinTransaction.begin();
        }
    }

    public void commit() {
        LOG.info("Committing transaction {}", id);
        gremlinTransaction.commit();
    }

    public void rollback() {
        LOG.info("Rolling back transaction {}", id);
        gremlinTransaction.rollback();
    }

    public void close() {
        gremlinTransaction.close();
    }

}

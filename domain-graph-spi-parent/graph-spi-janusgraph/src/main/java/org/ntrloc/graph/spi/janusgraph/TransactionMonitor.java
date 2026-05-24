package org.ntrloc.graph.spi.janusgraph;

import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphTransaction;
import org.janusgraph.graphdb.database.StandardJanusGraph;
import org.janusgraph.graphdb.database.management.ManagementSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class TransactionMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionMonitor.class);

    private final JanusGraph graph;
    private final Duration ttlInterval;

    private final Map<JanusGraphTransaction, Date> transactions = new HashMap<>();

    public TransactionMonitor(JanusGraph graph, @Value("${db.transaction.ttl-seconds:300}") Long ttlSeconds) {
        this.graph = graph;
        this.ttlInterval = Duration.ofSeconds(ttlSeconds);
    }

    public TransactionMonitor(JanusGraph graph) {
        this(graph, 300L);
    }

    @Scheduled(fixedRate = 30000)
    void pollTransactions() {
        var mgmt = graph.openManagement();
        try {
            var standard = (StandardJanusGraph) graph;

            var managementSystem = (ManagementSystem) mgmt;
            var managementTx = managementSystem.getWrappedTx();

            var openTransactions = standard.getOpenTransactions().stream().filter(tx -> !tx.equals(managementTx)).toList();
            transactions.keySet().retainAll(openTransactions);

            for (var tx : openTransactions) {
                if (transactions.containsKey(tx)) {
                    Date openTime = transactions.get(tx);
                    LOG.warn("Transaction {} was seen at {}", tx, openTime);
                    Duration openDuration = Duration.ofMillis(System.currentTimeMillis() - openTime.getTime());
                    if (openDuration.compareTo(ttlInterval) > 0) {
                        LOG.warn("Transaction {} has been open for {} seconds, rolling back and closing", tx, openDuration.getSeconds());
                        tx.rollback();
                        tx.close();
                    }
                } else {
                    transactions.put(tx, new Date());
                }
            }
        } finally {
            mgmt.rollback();
        }
    }
}

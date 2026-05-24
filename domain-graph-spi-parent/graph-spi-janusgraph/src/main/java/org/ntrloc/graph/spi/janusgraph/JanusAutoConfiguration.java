package org.ntrloc.graph.spi.janusgraph;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.ntrloc.graph.db.schema.GraphSchemaBackend;
import org.ntrloc.graph.spi.janusgraph.config.BerkeleyStorageConfiguration;
import org.ntrloc.graph.spi.janusgraph.config.CassandraStorageBackendConfiguration;
import org.ntrloc.graph.spi.janusgraph.config.IndexConfiguration;
import org.ntrloc.graph.spi.janusgraph.config.LuceneIndexConfiguration;
import org.ntrloc.graph.spi.janusgraph.config.StorageConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({StorageConfiguration.class, IndexConfiguration.class})
public class JanusAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(JanusAutoConfiguration.class);

    private final StorageConfiguration storageConfiguration;
    private final IndexConfiguration indexConfiguration;

    public JanusAutoConfiguration(StorageConfiguration storageConfiguration, IndexConfiguration indexConfiguration) {
        this.storageConfiguration = storageConfiguration;
        this.indexConfiguration = indexConfiguration;
    }

    @Bean
    public JanusGraph graph() {
        JanusGraphFactory.Builder builder = JanusGraphFactory.build();
        if (storageConfiguration.getBerkeley() != null) {
            BerkeleyStorageConfiguration berkeley = storageConfiguration.getBerkeley();
            builder = builder.set("storage.backend", "berkeleyje")
                    .set("storage.directory", berkeley.getDirectory());
        } else if (storageConfiguration.getCassandra() != null) {
            CassandraStorageBackendConfiguration cassandra = storageConfiguration.getCassandra();
            builder = builder.set("storage.backend", "cql")
                    .set("storage.hostname", cassandra.getHost());
        } else {
            LOG.warn("No storage backend configured, using in-memory storage");
            builder = builder.set("storage.backend", "inmemory");
        }
        builder.set("log.transactions", true);

        if (indexConfiguration.getLucene() != null) {
            LuceneIndexConfiguration lucene = indexConfiguration.getLucene();
            builder = builder.set("index.search.backend", "lucene")
                    .set("index.search.directory", lucene.getDirectory());
        } else {
            throw new RuntimeException("No index backend configured");
        }

        JanusGraph graph = builder.open();
        LOG.info("Graph opened: {}", graph);
        return graph;
    }

    @Bean
    public GraphTraversalSource traversalSource(JanusGraph graph) {
        return graph.traversal();
    }

    @Bean
    public GraphSchemaBackend graphSchemaBackend(JanusGraph graph) {
        return new JanusGraphSchemaBackend(graph);
    }

    @Bean
    public TransactionMonitor transactionMonitor(JanusGraph graph) {
        return new TransactionMonitor(graph);
    }
}

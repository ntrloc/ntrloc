package org.ntrloc.graph;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.ntrloc.graph.db.config.BerkeleyStorageConfiguration;
import org.ntrloc.graph.db.config.CassandraStorageBackend;
import org.ntrloc.graph.db.config.IndexConfiguration;
import org.ntrloc.graph.db.config.LuceneIndexConfiguration;
import org.ntrloc.graph.db.config.StorageConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({StorageConfiguration.class, IndexConfiguration.class})
public class JanusAutoConfiguration {

    private static final Logger LOG = LogManager.getLogger(JanusAutoConfiguration.class);

    private StorageConfiguration storageConfiguration;
    private IndexConfiguration indexConfiguration;

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
            CassandraStorageBackend cassandra = storageConfiguration.getCassandra();
            builder = builder.set("storage.backend", "cql")
                .set("storage.hostname", cassandra.getHost());
        } else {
            LOG.warn("No storage backend configured, using in-memory storage");
            builder = builder.set("storage.backend", "inmemory");
        }

        if (indexConfiguration.getLucene() != null) {
            LuceneIndexConfiguration lucene = indexConfiguration.getLucene();
            builder = builder.set("index.search.backend", "lucene")
                .set("index.search.directory", lucene.getDirectory());
        } else {
            throw new RuntimeException("No index backend configured");
        }
        return builder.open();
    }

    @Bean
    public GraphTraversalSource traversalSource(JanusGraph graph) {
        return graph.traversal();
    }

}

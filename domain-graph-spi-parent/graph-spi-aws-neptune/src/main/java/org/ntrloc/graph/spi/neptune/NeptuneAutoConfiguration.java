package org.ntrloc.graph.spi.neptune;

import com.amazonaws.neptune.auth.NeptuneNettyHttpSigV4Signer;
import com.amazonaws.neptune.auth.NeptuneSigV4SignerException;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.ntrloc.graph.db.schema.GraphSchemaBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import static org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource.traversal;

@Configuration
public class NeptuneAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(NeptuneAutoConfiguration.class);

    @Bean
    public GraphTraversalSource traversalSource() {
        LOG.info("Initializing Neptune remote cluster");

        String endpoint = "neptune-lab-cluster.cluster-cxtmgd1crjkf.us-east-1.neptune.amazonaws.com";
        String region   = System.getProperty("aws.region", "us-east-1");

        Cluster cluster = Cluster.build()
                .addContactPoint(endpoint)
                .port(8182)
                .enableSsl(true)
                .sslSkipCertValidation(true)
                .requestInterceptor(r -> {
                    try {
                        new NeptuneNettyHttpSigV4Signer(region, DefaultCredentialsProvider.create()).signRequest(r);
                    } catch (NeptuneSigV4SignerException e) {
                        throw new RuntimeException("Failed to sign request", e);
                    }
                    return r;
                })
                .create();

        // Bind the traversal source to the remote cluster
        return traversal().withRemote(DriverRemoteConnection.using(cluster));
    }

    @Bean
    public GraphSchemaBackend graphSchemaBackend() {
        return new NeptuneSchemaBackend();
    }

}

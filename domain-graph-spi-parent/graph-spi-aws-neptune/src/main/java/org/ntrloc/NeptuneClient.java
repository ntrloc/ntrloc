package org.ntrloc;

import com.amazonaws.neptune.auth.NeptuneNettyHttpSigV4Signer;
import com.amazonaws.neptune.auth.NeptuneSigV4SignerException;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import static org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource.traversal;

public class NeptuneClient {

    public static void main(String[] args) throws Exception {
        String endpoint = "neptune-lab-cluster.cluster-cxtmgd1crjkf.us-east-1.neptune.amazonaws.com";
        String region   = System.getProperty("aws.region", "us-east-1");

        Cluster cluster = Cluster.build()
                .addContactPoint(endpoint)
                .port(8182)
                .enableSsl(true)
                .sslSkipCertValidation(true)
                .requestInterceptor(r -> {
                    try {
                        new NeptuneNettyHttpSigV4Signer(region, DefaultCredentialsProvider.create())
                                .signRequest(r);
                    } catch (NeptuneSigV4SignerException e) {
                        throw new RuntimeException("Failed to sign request", e);
                    }
                    return r;
                })
                .create();

        // Bind the traversal source to the remote cluster
        GraphTraversalSource g = traversal()
                .withRemote(DriverRemoteConnection.using(cluster));

        try {
            g.V().drop().iterate();

            // Count all vertices
            long count = g.V().count().next();
            System.out.println("Vertex count: " + count);

            // Add a vertex
            g.addV("person")
                    .property("name", "Alice")
                    .property("age", 30)
                    .iterate();

            // Add another and an edge
            g.addV("person")
                    .property("name", "Bob")
                    .property("age", 25)
                    .iterate();

            g.addE("knows")
                    .from(__.V().has("person", "name", "Alice"))
                    .to(__.V().has("person", "name", "Bob"))
                    .iterate();

            // Query with type-safe traversal
            g.V().hasLabel("person")
                    .order().by("age")
                    .valueMap("name", "age")
                    .toList()
                    .forEach(m -> System.out.println("Person: " + m));

            // Traverse the edge
            g.V().has("person", "name", "Alice")
                    .out("knows")
                    .values("name")
                    .toList()
                    .forEach(name -> System.out.println("Alice knows: " + name));

        } finally {
            g.close();        // closes the DriverRemoteConnection
            cluster.close();
        }
    }
}
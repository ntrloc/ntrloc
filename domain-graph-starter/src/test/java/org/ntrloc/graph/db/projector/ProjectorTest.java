package org.ntrloc.graph.db.projector;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.NoSuchElementException;
import java.util.UUID;

@DisplayName("A projector")
class ProjectorTest {

    private JanusGraph janusGraph;
    private GraphTraversalSource traversalSource;

    @BeforeEach
    void init() {
        if (janusGraph != null && janusGraph.isOpen()) {
            janusGraph.close();
        }

        String indexPath;
        do {
            String tmpId = UUID.randomUUID().toString().substring(0, 8);
            indexPath = "target/db/lucene-test-" + tmpId;
        } while (new File(indexPath).exists());


        File indexDir = new File(indexPath);
        janusGraph = JanusGraphFactory.build()
                .set("storage.backend", "inmemory")
                .set("index.search.backend", "lucene")
                .set("index.search.directory", indexPath)
                .set("cache.tx-cache-size", 0)
                .open();
        traversalSource = janusGraph.traversal();
        try {
            traversalSource.V().drop().next();
        } catch (NoSuchElementException nee) { }

        janusGraph.tx().close();

        traversalSource.tx().begin();
        traversalSource
                .addV("Person").property("name", "John").property("age", 30).as("john")
                .addV("Person").property("name", "Jane").property("age", 22).as("jane")
                .addV("Person").property("name", "Jenny").property("age", 22).as("jenny")
                .addV("Person").property("name", "Bill").property("age", 25).as("bill")
                .addV("Person").property("name", "Jack").as("jack")
                .addE("knows").from("john").to("jane")
                .addE("knows").from("john").to("jenny")
                .addE("knows").from("jane").to("bill")
                .addE("knows").from("bill").to("john")
                .addE("knows").from("jenny").to("jack")
                .iterate();
        traversalSource.tx().commit();
    }

    @Test
    @DisplayName("should execute a default vertex projection")
    void testDefaultVertexProjection () {
        VertexProjectionSpec spec = new VertexProjectionSpec(traversalSource, "Person")
                //.filter(FilterSpecFactory.hasPredicate("age", P.between(22, 31)))
                //.sort(Tuple.of("age", Order.asc), Tuple.of("name", Order.desc))
                .properties("name", "age")
                .edge("knows",
                        new EdgeProjectionSpec("knows", Direction.OUT, "Person", new VertexProjectionSpec()))
                .edge("known-by",
                    new EdgeProjectionSpec("knows", Direction.IN, "Person", new VertexProjectionSpec()));
        var iterator = spec.construct();
        while (iterator.hasNext()) {
            var v = iterator.next();
            System.out.println(String.format("name: %s, age: %s, knows: %s, known by: %s", v.stringProperty("name"), v.intProperty("age"), v.projectionProperty("knows"), v.projectionProperty("known-by")));
        }

    }


}

package org.ntrloc.graph.db.projector;

import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.projector.filter.AndFilterSpec;
import org.ntrloc.graph.db.projector.filter.PropertyPredicateFilterSpec;

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
                .addV("Person").property("name", "Jenny").property("age", 11).as("jenny")
                .addV("Person").property("name", "Bill").property("age", 25).as("bill")
                .addV("Person").property("name", "Jack").property("age", 4).as("jack")
                .addE("knows").property("met", 2011).from("john").to("jane")
                .addE("knows").property("met", 2020).from("john").to("jenny")
                .addE("knows").property("met", 2023).from("jane").to("bill")
                .addE("knows").property("met", 2000).from("bill").to("john")
                .addE("knows").property("met", 2025).from("jenny").to("jack")
                .addE("knows").property("met", 2000).from("bill").to("jane")
                .iterate();
        traversalSource.tx().commit();
    }

    @Test
    @DisplayName("should execute a default vertex projection")
    void testDefaultVertexProjection () {
        VertexSpec spec = new VertexSpec(traversalSource, "Person")
                .filter(PropertyPredicateFilterSpec.with("age", P.between(22, 31)))
                .sort(VertexSort.on("age", Order.asc), VertexSort.on("name", Order.desc));
                var knownByProjection = new VertexProjectionSpec().edge("known-by",
                        new EdgeSpec("knows", Direction.IN, "Person").projection(new VertexProjectionSpec()).sort(EdgeSort.vertex("age", Order.desc), EdgeSort.edge("met", Order.desc)));

                spec.projection()
                    .properties("name", "age")
                    .edge("knows",
                        new EdgeSpec("knows", Direction.OUT, "Person").projection(knownByProjection).sort(EdgeSort.vertex("age", Order.desc), EdgeSort.edge("met", Order.asc)))
                    ;
        var iterator = spec.construct();
        while (iterator.hasNext()) {
            var v = iterator.next();
            System.out.println(String.format("name: %s, age: %s, knows: %s", v.stringProperty("name"), v.intProperty("age"), v.projectionProperty("knows")));
        }

    }

    @Test
    @DisplayName("should filter edge projections by property")
    void testFilterEdgeProjections() {

        VertexSpec spec = new VertexSpec(traversalSource, "Person");
        spec.projection()
                .properties("name", "age")
                .edge("knows",
                        new EdgeSpec("knows", Direction.OUT, "Person", false).projection(new VertexProjectionSpec())
                );
        var iterator = spec.construct();
        while (iterator.hasNext()) {
            var v = iterator.next();
            System.out.println(String.format("name: %s, age: %s, knows: %s", v.stringProperty("name"), v.intProperty("age"), v.projectionProperty("knows")));
        }
    }

    @Test
    @DisplayName("should filter edge projections by edge properties and target vertex properties")
    void testFilterEdgeProjectionsWithFilter() {
        VertexSpec spec = new VertexSpec(traversalSource, "Person");
        spec.projection()
                .properties("name", "age")
                .edge("knows",
                        new EdgeSpec("knows", Direction.OUT, "Person")
                                .filter(AndFilterSpec.with(PropertyPredicateFilterSpec.with("met", 2025), PropertyPredicateFilterSpec.with(__.inV(), "age", 4)))
                                .projection(new VertexProjectionSpec())
                )

        ;
        var iterator = spec.construct();
        while (iterator.hasNext()) {
            var v = iterator.next();
            System.out.println(String.format("name: %s, age: %s, knows: %s", v.stringProperty("name"), v.intProperty("age"), v.projectionProperty("knows")));
        }
    }


}

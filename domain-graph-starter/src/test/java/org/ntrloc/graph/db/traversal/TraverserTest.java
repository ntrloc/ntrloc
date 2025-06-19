package org.ntrloc.graph.db.traversal;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("A traverser")
class TraverserTest {

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
                .addV("Person").property("name", "Bill").property("age", 25).as("bill")
                .addE("knows").from("john").to("jane")
                .addE("knows").from("jane").to("bill")
                .addE("knows").from("bill").to("john")
                .iterate();
        traversalSource.tx().commit();
    }

    @Test
    @DisplayName("should execute a default vertex projection")
    void testDefaultVertexProjection () {
        Traverser traverser = new Traverser(traversalSource.V().hasLabel("Person"));
        var iterator = traverser.iterator();
        assertTrue(iterator.hasNext(), "no vertices found");
        while (iterator.hasNext()) {
            var v = iterator.next();
            assertNotNull(v.stringProperty("name"), "no name found");
            assertNotNull(v.intProperty("age"), "no age found");
            assertFalse(v.hasInboundEdges(), "found inbound edges");
            assertFalse(v.hasOutboundEdges(), "found outbound edges");
        }
    }

    @Test
    @DisplayName("should execute a vertex projection with specific properties")
    void testVertexProjectionWithSpecificProperties () {
        Traverser traverser = new Traverser(new VertexProjectionSpec(traversalSource.V()).properties("age"));
        var iterator = traverser.iterator();
        assertTrue(iterator.hasNext(), "no vertices found");
        while (iterator.hasNext()) {
            var v = iterator.next();
            assertNull(v.stringProperty("name"), "name found");
            assertNotNull(v.intProperty("age"), "no age found");
        }
    }

    @Test
    @DisplayName("should execute a vertex projection with outbound and outbound edges")
    void testVertexProjectionWithOutboundEdges () {
        var vspec = new VertexProjectionSpec(traversalSource.V())
                .edges(
                    new EdgeProjectionSpec("knows", EdgeProjectionSpec.Direction.IN),
                    new EdgeProjectionSpec("knows", EdgeProjectionSpec.Direction.OUT)
                );
        Traverser traverser = new Traverser(vspec);
        var iterator = traverser.iterator();
        assertTrue(iterator.hasNext(), "no vertices found");
        while (iterator.hasNext()) {
            var v = iterator.next();
            List<EdgeProjection> outbound = v.getOutboundEdges("knows");
            assertNotNull(outbound, "outbound edges not found");

            List<EdgeProjection> inbound = v.getInboundEdges("knows");
            assertNotNull(inbound, "inbound edges not found");
        }
    }

}

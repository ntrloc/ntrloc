package org.ntrloc.graph.db.pathfinder;


import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.NoSuchElementException;
import java.util.UUID;

public class PathfinderTest {

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
                // create groups
                .addV("Group").property("name", "Top Group").as("topgroup")
                .addV("Group").property("name", "Group 1").as("group1")
                .addV("Group").property("name", "Group 2").as("group2")
                .addV("Group").property("name", "IT").as("itgroup")

                // create people
                .addV("Person").property("name", "John").as("john")
                .addV("Person").property("name", "Jane").as("jane")
                .addV("Person").property("name", "Bill").as("bill")

                // create systems
                .addV("System").property("name", "Outlook").as("outlook")

                // link groups to groups
                .addE("contains").from("topgroup").to("group1")
                .addE("contains").from("topgroup").to("group2")
                .addE("contains").from("topgroup").to("itgroup")

                // link groups to people
                .addE("contains").from("group1").to("john")
                .addE("contains").from("group2").to("jane")
                .addE("contains").from("itgroup").to("bill")

                // link users to systems
                .addE("uses").from("john").to("outlook")
                .addE("uses").from("jane").to("outlook")

                // link managers to systems
                .addE("manages").from("bill").to("outlook")


                .iterate();
        traversalSource.tx().commit();
    }

    @Test
    void testPathfinder() {
        // find all paths from Group vertices to the system called "outlook"
        var paths = traversalSource.V().hasLabel("System").has("name", "Outlook").as("system")
                .repeat(
                        __.bothE().as("e")
                        .otherV().as("v")
                        .simplePath()
                )
                .until(__.and(__.hasLabel("Group"), __.not(__.in().hasLabel("Group")))) // stop tracing the path when you arrive at a group that doesn't have a parent group
                .path()

                // assuming the start and end of the path is always a vertex
                .by(
                        __.project("label", "id", "props").by(__.label()).by(__.id()).by(__.valueMap())

                ) // vertices get the label, id, and properties
                .by(
                        __.project("label", "in", "out", "props").by(__.label()).by(__.outV().id()).by(__.inV().id()).by(__.valueMap())
                ) // edge get the label, in vertex id, out vertex id, and properties
                ;
        while (paths.hasNext()) {
            var next = paths.next();
            System.out.println(next);
        }
    }

}

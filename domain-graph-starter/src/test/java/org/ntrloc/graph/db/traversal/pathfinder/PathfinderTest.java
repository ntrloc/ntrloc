package org.ntrloc.graph.db.traversal.pathfinder;


import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.traversal.pathfinder.Pathfinder;

import java.io.File;
import java.util.NoSuchElementException;
import java.util.UUID;

class PathfinderTest {

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
                // NOTE: we're doing this the "ntrloc" way, where relationships are expressed with vertices

                .addV("CONTAINS").as("topgroup_contains_group1")
                .addE("contains_in").from("topgroup").to("topgroup_contains_group1")
                .addE("contains_out").from("topgroup_contains_group1").to("group1")

                .addV("CONTAINS").as("topgroup_contains_group2")
                .addE("contains_in").from("topgroup").to("topgroup_contains_group2")
                .addE("contains_out").from("topgroup_contains_group2").to("group2")

                .addV("CONTAINS").as("topgroup_contains_itgroup")
                .addE("contains_in").from("topgroup").to("topgroup_contains_itgroup")
                .addE("contains_out").from("topgroup_contains_itgroup").to("itgroup")

                // link groups to people
                .addV("CONTAINS").as("group1_contains_john")
                .addE("contains_in").from("group1").to("group1_contains_john")
                .addE("contains_out").from("group1_contains_john").to("john")

                .addV("CONTAINS").as("group2_contains_jane")
                .addE("contains_in").from("group2").to("group2_contains_jane")
                .addE("contains_out").from("group2_contains_jane").to("jane")

                .addV("CONTAINS").as("itgroup_contains_bill")
                .addE("contains_in").from("itgroup").to("itgroup_contains_bill")
                .addE("contains_out").from("itgroup_contains_bill").to("bill")

                // link users to systems
                .addV("USES").as("john_uses_outlook")
                .addE("uses_in").from("john").to("john_uses_outlook")
                .addE("uses_out").from("john_uses_outlook").to("outlook")

                .addV("USES").as("jane_uses_outlook")
                .addE("uses_in").from("jane").to("jane_uses_outlook")
                .addE("uses_out").from("jane_uses_outlook").to("outlook")


                // link managers to systems
                .addV("MANAGES").as("bill_manages_outlook")
                .addE("manages_in").from("bill").to("bill_manages_outlook")
                .addE("manages_out").from("bill_manages_outlook").to("outlook")

                .iterate();
        traversalSource.tx().commit();
    }

    @Test
    void testPathfinder() {
        var pathfinder = new Pathfinder(traversalSource, "System", "Group");
        var nodes = pathfinder.find();
        while (nodes.hasNext()) {
            var next = nodes.next();
            System.out.println(next);
        }
    }

}

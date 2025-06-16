package org.nterloc.graph.db;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.janusgraph.core.Cardinality;
import org.janusgraph.core.JanusGraphFactory;
import org.janusgraph.core.PropertyKey;
import org.janusgraph.core.VertexLabel;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.janusgraph.core.schema.Mapping;
import org.janusgraph.core.schema.SchemaStatus;
import org.janusgraph.graphdb.database.management.GraphIndexStatusReport;
import org.janusgraph.graphdb.database.management.ManagementSystem;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.janusgraph.core.attribute.Text.textRegex;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ExperimentsTest {

    private static final Logger LOG = LoggerFactory.getLogger(ExperimentsTest.class);

    @Test
    public void simpleIndexTest() throws InterruptedException {

        var newGraph = JanusGraphFactory.build()
                .set("storage.backend", "inmemory")
                .set("index.search.backend", "lucene")
                .set("index.search.directory", "db/lucene-test")
                //.set("query.force-index", "true")
                .open();

        try {
            String photoIndexName = "photo";
            String photographerIndexName = "photographer";

            JanusGraphManagement management = newGraph.openManagement();

            VertexLabel photoLabel = management.getOrCreateVertexLabel("Photo");
            VertexLabel photographerLabel = management.getOrCreateVertexLabel("Photographer");

            PropertyKey photoAgeKey = management.makePropertyKey("photo_age").dataType(Integer.class).make();
            PropertyKey photoNameKey = management.makePropertyKey("photo_name").dataType(String.class).make();
            management.buildIndex(photoIndexName, Vertex.class)
                    .addKey(photoAgeKey)
                    .addKey(photoNameKey)
                    .indexOnly(photoLabel)
                    .buildMixedIndex("search");

            PropertyKey photographerAgeKey = management.makePropertyKey("photographer_age").dataType(String.class).make();
            PropertyKey photographerNameKey = management.makePropertyKey("photographer_name").dataType(String.class).make();
            PropertyKey blahKey = management.makePropertyKey("blah").dataType(Integer.class).cardinality(Cardinality.LIST).make();

            management.buildIndex(photographerIndexName, Vertex.class)
                    .addKey(photographerAgeKey, Mapping.STRING.asParameter())
                    .addKey(blahKey)
                    .indexOnly(photographerLabel)
                    .buildMixedIndex("search");

            management.commit();

            LOG.info("Index created");

            GraphIndexStatusReport report1 = ManagementSystem.awaitGraphIndexStatus(newGraph, photoIndexName).status(SchemaStatus.ENABLED).call();
            GraphIndexStatusReport report2 = ManagementSystem.awaitGraphIndexStatus(newGraph, photographerIndexName).status(SchemaStatus.ENABLED).call();
            LOG.info("Index ready status: {} {}", report1, report2);

            management = newGraph.openManagement();
            LOG.info("Graph schema: {}", management.printSchema());

            var trav = newGraph.traversal();
            var tx = trav.tx();
            trav
                    .addV("Photo").as("photo1").property(Map.of("photo_age", 13, "photo_name", "bob"))
                    .addV("Photo").as("photo2").property(Map.of("photo_age", 13, "photo_name", "bob smith"))
                    .addV("Photographer").as("photographer").property(Map.of("photographer_age", "bronze", "photographer_name", "Bill")).property("blah", 1).property("blah", 2).property( "blah", 3)
                    .addE("created").from("photographer").to("photo1")
                    .addE("created").from("photographer").to("photo2")
                    .iterate();
            tx.commit();


            var test = newGraph.traversal().V().hasLabel("Photographer").has("blah", 2).valueMap();
            if (test.hasNext()) {
                LOG.info(test.next().toString());
            } else {
                LOG.warn("Nope");
            }

            LOG.info("Running age only query");
            var metrics1 = newGraph.traversal().V().hasLabel("Photo").has("photo_age", 13).profile().next();
            LOG.info("Got age-only metrics {}", metrics1);

            LOG.info("Running age and name query");
            var metrics2 = newGraph.traversal().V().hasLabel("Photo").has("photo_name", textRegex("^bob$")).has("photo_age", 13).profile().next();
            LOG.info("Got age and neme metrics {}", metrics2);

            var count = newGraph.traversal().V().hasLabel("Photo").has("photo_age", 13).has("photo_name", textRegex("^bob$")).count().next();
            assertEquals(1, count);

            var metrics3 = newGraph.traversal().V().hasLabel("Photographer").has("photographer_age", "bronze").profile().next();
            LOG.info("Got photographer age-only metrics {}", metrics3);

            var photographerNode = newGraph.traversal().V()
                    .hasLabel("Photographer")
                    .has("photographer_age", textRegex("bronz.+")).
                    project("self", "photos")
                    .by(__.elementMap())
                    .by(__.out("created").elementMap().fold())
                    .next();
            LOG.info("Got photographer node {}", photographerNode);

        } finally {
            newGraph.close();
        }

    }

    @Test
    void testCopyVertex() {
        var newGraph = JanusGraphFactory.build()
                .set("storage.backend", "inmemory")
                .open();

        var traversal = newGraph.traversal();
        var tx = traversal.tx();
        tx.begin();
        Vertex startVertex = traversal.addV("Product").property("name", "Test product").property(VertexProperty.Cardinality.list, "GRA", 3).property(VertexProperty.Cardinality.list, "GRA", 5).next();
        tx.commit();
        tx.close();

        traversal = newGraph.traversal();
        tx = traversal.tx();
        tx.begin();
        Vertex endVertex = traversal.V(startVertex.id()).as("start")
                .addV(__.select("start").label()).as("new")
                .sideEffect(
                        __.select("start").properties().as("startProps").select("new").property(__.select("startProps").key(), __.select("startProps").value())
                ).
                addE("is-update-to").from("new").to("start")
                .outV()
                .next();
        tx.commit();
        tx.close();
        LOG.info("Got new vertex {}", endVertex);

        var newProps = newGraph.traversal().V(endVertex.id()).project("id", "values", "in", "out").by(__.id()).by(__.valueMap()).by(__.in()).by(__.out()).next();
        LOG.info("Got new props {}", newProps);
    }

    @Test
    void testNodeProperty() {
        var newGraph = JanusGraphFactory.build()
                .set("storage.backend", "inmemory")
                .open();

        var traversal = newGraph.traversal();
        var tx = traversal.tx();
        tx.begin();

        traversal
                .addV("Cover").as("cover")
                .property("name", "Test cover")
                .addV("system:data").as("data")
                .property("dataprop", "testdatavalue")
                .property("sha1hash", "abc123")
                .addV("system:data").as("thumbnail")
                .property("md5hash", "xyz")
                .addE("system:nodeProperty")
                .property("propertyName", "data")
                .from("cover").to("data")
                .addE("system:nodeProperty")
                .property("propertyName", "thumbnail")
                .from("cover").to("thumbnail")

                .iterate();

        tx.commit();
        tx.close();

        var testResult = traversal.V().hasLabel("Cover")
                .project("properties", "nodeProps")
                .by(__.valueMap())
                .by(__.outE("system:nodeProperty")
                        .project("name", "vertex")
                        .by(__.values("propertyName"))
                        .by(__.inV().project("id", "label", "properties").by(__.id()).by(__.label()).by(__.valueMap()))
                        .group()
                        .by("name")
                        .by(__.select("vertex"))
                )
                .next();

        LOG.info("Got test result {}", testResult);
    }

}

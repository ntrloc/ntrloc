package org.ntrloc.graph.db.projector;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.PropertyConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@DisplayName("A projector")
class ProjectorTest {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectorTest.class);

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
                .set("cache.tx-cache-size", 0)
                .open();
        traversalSource = janusGraph.traversal();
        try {
            traversalSource.V().drop().next();
        } catch (NoSuchElementException nee) { }

        janusGraph.tx().close();

        traversalSource.tx().begin();
        traversalSource
                // entity nodes
                .addV("Photo")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "abc1")
                    .property(PropertyConstants.NODE_TYPE_PROPERTY, "Photo")
                    .property("Photo_name", "photo1")
                    .property("Photo_colorspace", "B&W")

                    .as("photo1")
                .addV("Photo")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "abc2")
                    .property(PropertyConstants.NODE_TYPE_PROPERTY, "Photo")
                    .property("Photo_name", "photo2")
                    .property("Photo_colorspace", "RGB")

                    .as("photo2")
                .addV("Photographer")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "abc3")
                    .property(PropertyConstants.NODE_TYPE_PROPERTY, "Photographer")
                    .property("Photographer_name", "Bill")
                    .as("photographer1")

                // link node
                .addV("CREATED")
                    .property("date", "2020-01-01")
                    .as("createdProp1")
                .addV("CREATED")
                    .property("date", "2025-01-01")
                    .as("createdProp2")

                // connection from entity->link->entity
                .addE("CREATED-in").from("photographer1").to("createdProp1")
                .addE("CREATED-out").from("createdProp1").to("photo1")
                .addE("CREATED-in").from("photographer1").to("createdProp2")
                .addE("CREATED-out").from("createdProp2").to("photo2")

                .iterate();
        traversalSource.tx().commit();
    }

    @Test
    @DisplayName("should execute a simple node projection")
    void testSimpleNodeProjection() {
        Projector projector = new Projector(traversalSource);
        NodeProjectionSpec spec = new NodeProjectionSpec(LabelSelector.on("Photo"))
                .properties(List.of("Photo_name", "Photo_colorspace"));
        Iterable<NodeProjection> projections = projector.project(spec);
        for (NodeProjection projection: projections) {
            LOG.info("Got projection {}", projection);
        }
    }

    @Test
    @DisplayName("should execute a node projection with links")
    void testNodeProjectionWithLinks() {
        Projector projector = new Projector(traversalSource);
        NodeProjectionSpec spec = new NodeProjectionSpec(LabelSelector.on("Photo"))
                .properties(List.of("Photo_name", "Photo_colorspace"));
        Iterable<NodeProjection> projections = projector.project(spec);
        for (NodeProjection projection: projections) {
            LOG.info("Got projection {}", projection);
        }
    }

}

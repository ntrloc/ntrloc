package org.ntrloc.graph.db;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.impl.ItemManagerImpl;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.db.storage.BinaryStorageAdapter;
import org.ntrloc.graph.db.traversal.mutator.Mutator;
import org.ntrloc.graph.db.traversal.projector.Projector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("An entity manager")
public class ItemManagerProjectionTest {

    private static final Logger LOG = LoggerFactory.getLogger(ItemManagerProjectionTest.class);

    private GraphTraversalSource traversalSource;
    private JanusGraph janusGraph;
    private ItemManager manager;

    @BeforeEach
    void init() throws IOException {
        if (janusGraph != null && janusGraph.isOpen()) {
            janusGraph.close();
        }

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
                .property(PropertyConstants.UNIQUE_ID_PROPERTY, "p1")
                .property(PropertyConstants.NODE_TYPE_PROPERTY, "Photo")
                .property("Photo_name", "photo1")
                .property("Photo_colorspace", "B&W")
                .as("photo1")

                .addV("Photo")
                .property(PropertyConstants.UNIQUE_ID_PROPERTY, "p2")
                .property(PropertyConstants.NODE_TYPE_PROPERTY, "Photo")
                .property("Photo_name", "photo2")
                .property("Photo_colorspace", "RGB")
                .as("photo2")

                .addV("Photographer")
                .property(PropertyConstants.UNIQUE_ID_PROPERTY, "ph3")
                .property(PropertyConstants.NODE_TYPE_PROPERTY, "Photographer")
                .property("Photographer_name", "Bill")
                .property("Photographer_age", 30)
                .as("photographer1")

                .addV("Photographer")
                .property(PropertyConstants.UNIQUE_ID_PROPERTY, "ph4")
                .property(PropertyConstants.NODE_TYPE_PROPERTY, "Photographer")
                .property("Photographer_name", "Jack")
                .property("Photographer_age", 55)
                .as("photographer2")

                .addV("Lightbox")
                .property(PropertyConstants.UNIQUE_ID_PROPERTY, "lb1")
                .property(PropertyConstants.NODE_TYPE_PROPERTY, "Lightbox")
                .property("Lightbox_name", "lightbox1")
                .as("lb1")

                .addV("Agency")
                .property(PropertyConstants.UNIQUE_ID_PROPERTY, "a3")
                .property(PropertyConstants.NODE_TYPE_PROPERTY, "Agency")
                .property("Agency_name", "Some Agency")
                .as("a3")

                // link node
                .addV("CREATED")
                .property("date", "2020-01-01")
                .property(PropertyConstants.UNIQUE_ID_PROPERTY, "created1")
                .as("createdProp1")
                .addV("CREATED")
                .property("date", "2025-01-01")
                .property(PropertyConstants.UNIQUE_ID_PROPERTY, "created2")
                .as("createdProp2")
                .addV("CREATED")
                .property("date", "2025-09-01")
                .property(PropertyConstants.UNIQUE_ID_PROPERTY, "created3")
                .as("createdProp3")
                .addV("EMPLOYS")
                .property(PropertyConstants.UNIQUE_ID_PROPERTY, "employs1")
                .as("employsProp1")

                // connection from entity->link->entity
                .addE("CREATED-in").from("photographer1").to("createdProp1")
                .addE("CREATED-out").from("createdProp1").to("photo1")
                .addE("CREATED-in").from("photographer1").to("createdProp2")
                .addE("CREATED-out").from("createdProp2").to("photo2")
                .addE("CREATED-in").from("photographer1").to("createdProp3")
                .addE("CREATED-out").from("createdProp3").to("lb1")
                .addE("EMPLOYS-in").from("a3").to("employsProp1")
                .addE("EMPLOYS-out").from("employsProp1").to("photographer1")

                .iterate();
        traversalSource.tx().commit();

        BinaryStorageAdapter adapter = mock(BinaryStorageAdapter.class);
        SchemaManager schemaManager = mock(SchemaManager.class);
        Mutator mutator = new Mutator(traversalSource);
        Projector projector = new Projector(traversalSource);
        manager = new ItemManagerImpl(traversalSource, adapter, schemaManager, mutator, projector);
    }

    @Test
    @DisplayName("should be able to return all entities of a given type")
    void testRetrieveEntities() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec("Photo")
                .properties(List.of("name", "colorspace"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(2, list.size());
        for (ItemProjection projection: list) {
            assertNotNull(projection.getId());
            assertNotNull(projection.getNodeType());
            assertTrue(projection.getProperties().containsKey("name"));
            assertTrue(projection.getProperties().containsKey("colorspace"));
        }
    }


}

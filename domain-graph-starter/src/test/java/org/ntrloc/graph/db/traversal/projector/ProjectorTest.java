package org.ntrloc.graph.db.traversal.projector;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.PropertyConstants;
import org.ntrloc.graph.db.language.selectors.HasPropertyValueSelector;
import org.ntrloc.graph.db.language.selectors.predicate.EqualsPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.GreaterThanPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.LessThanPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.NotEqualsPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    }

    @Test
    @DisplayName("should execute a simple item projection")
    void testSimpleItemProjection() {
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

    @Test
    @DisplayName("should project links by a specific item type")
    void testProjectLinksByNodeType() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec("Photographer")
                .properties(List.of("name"))
                .link("created", new LinkProjectionSpec("CREATED", Direction.OUT, "Photo").properties(List.of("date")))
                .link("worksFor", new LinkProjectionSpec("EMPLOYS", Direction.IN, "Agency"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(2, list.size());
        Optional<ItemProjection> photographer = list.stream().filter(p -> !p.getLinks().get("created").isEmpty() && !p.getLinks().get("worksFor").isEmpty()).findFirst();
        assertTrue(photographer.isPresent());
    }

    @Test
    @DisplayName("should project nodes that have a property value (equals)")
    void testProjectNodesByEquals() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec("Photographer")
                .select(HasPropertyValueSelector.of("name", EqualsPredicate.of("Jack")))
                .properties(List.of("name"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Jack"), photographer.getProperties().get("name"));
    }

    @Test
    @DisplayName("should project nodes that have a property value (not equals)")
    void testProjectNodesByNotEquals() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec("Photographer")
                .select(HasPropertyValueSelector.of("name", NotEqualsPredicate.of("Jack")))
                .properties(List.of("name"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Bill"), photographer.getProperties().get("name"));
    }

    @Test
    @DisplayName("should project nodes that have a property value (less than)")
    void testProjectNodesByLessThan() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec("Photographer")
                .select(HasPropertyValueSelector.of("age", LessThanPredicate.of(50)))
                .properties(List.of("name"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Bill"), photographer.getProperties().get("name"));
    }

    @Test
    @DisplayName("should project nodes that have a property value (greater than)")
    void testProjectNodesByGreaterThan() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec("Photographer")
                .select(HasPropertyValueSelector.of("age", GreaterThanPredicate.of(50)))
                .properties(List.of("name"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Jack"), photographer.getProperties().get("name"));
    }

}

package org.ntrloc.graph.db.traversal.projector;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.PropertyConstants;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.LinkProjectionSpec;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.language.selectors.HasPropertyValueSelector;
import org.ntrloc.graph.db.language.selectors.LabelSelector;
import org.ntrloc.graph.db.language.selectors.predicate.EqualsPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.GreaterThanPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.LessThanPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.NotEqualsPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.WithinPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.WithoutPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
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
                    .property(PropertyConstants.ITEM_TYPE_PROPERTY, "Photo")
                    .property(PropertyConstants.VERSION_PROPERTY, 1)
                    .property("photoName", "photo1")
                    .property("photoColorspace", "B&W")
                    .as("photo1")

                .addV("Photo")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "p2")
                    .property(PropertyConstants.ITEM_TYPE_PROPERTY, "Photo")
                    .property(PropertyConstants.VERSION_PROPERTY, 1)
                    .property("photoName", "photo2")
                    .property("photoColorspace", "RGB")
                    .as("photo2")

                .addV("Photographer")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "ph3")
                    .property(PropertyConstants.ITEM_TYPE_PROPERTY, "Photographer")
                    .property(PropertyConstants.VERSION_PROPERTY, 1)
                    .property("photographerName", "Bill")
                    .property("photographerAge", 30)
                    .as("photographer1")

                .addV("Photographer")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "ph4")
                    .property(PropertyConstants.ITEM_TYPE_PROPERTY, "Photographer")
                    .property(PropertyConstants.VERSION_PROPERTY, 1)
                    .property("photographerName", "Jack")
                    .property("photographerAge", 55)
                    .as("photographer2")

                .addV("Lightbox")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "lb1")
                    .property(PropertyConstants.ITEM_TYPE_PROPERTY, "Lightbox")
                    .property(PropertyConstants.VERSION_PROPERTY, 1)
                    .property("lightboxName", "lightbox1")
                    .as("lb1")

                .addV("Agency")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "a3")
                    .property(PropertyConstants.ITEM_TYPE_PROPERTY, "Agency")
                    .property(PropertyConstants.VERSION_PROPERTY, 1)
                    .property("agencyName", "Some Agency")
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
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new LabelSelector("Photo"))
                .properties(List.of("photoName", "photoColorspace"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(2, list.size());
        for (ItemProjection projection: list) {
            assertNotNull(projection.getId());
            assertNotNull(projection.getItemType());
            assertTrue(projection.getProperties().containsKey("photoName"));
            assertTrue(projection.getProperties().containsKey("photoColorspace"));
        }
    }

    @Test
    @DisplayName("should project links by a specific item type")
    void testProjectLinksByNodeType() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new LabelSelector("Photographer"))
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
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new LabelSelector("Photographer"))
                .filter(HasPropertyValueSelector.of("photographerName", EqualsPredicate.of("Jack")))
                .properties(List.of("photographerName"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Jack"), photographer.getProperties().get("photographerName"));
    }

    @Test
    @DisplayName("should project nodes that have a property value (not equals)")
    void testProjectNodesByNotEquals() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new LabelSelector("Photographer"))
                .filter(HasPropertyValueSelector.of("photographerName", NotEqualsPredicate.of("Jack")))
                .properties(List.of("photographerName"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Bill"), photographer.getProperties().get("photographerName"));
    }

    @Test
    @DisplayName("should project nodes that have a property value (less than)")
    void testProjectNodesByLessThan() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new LabelSelector("Photographer"))
                .filter(HasPropertyValueSelector.of("photographerAge", LessThanPredicate.of(50)))
                .properties(List.of("photographerName"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Bill"), photographer.getProperties().get("photographerName"));
    }

    @Test
    @DisplayName("should project nodes that have a property value (greater than)")
    void testProjectNodesByGreaterThan() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new LabelSelector("Photographer"))
                .filter(HasPropertyValueSelector.of("photographerAge", GreaterThanPredicate.of(50)))
                .properties(List.of("photographerName"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Jack"), photographer.getProperties().get("photographerName"));
    }

    @Test
    @DisplayName("should project nodes that have a property value (string within)")
    void testProjectNodesByStringWithin() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new LabelSelector("Photographer"))
                .filter(HasPropertyValueSelector.of("photographerName", WithinPredicate.on(List.of("Bob", "Bill"))))
                .properties(List.of("photographerName"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Bill"), photographer.getProperties().get("photographerName"));
    }

    @Test
    @DisplayName("should project nodes that have a property value (number within)")
    void testProjectNodesByNumberWithin() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new LabelSelector("Photographer"))
                .filter(HasPropertyValueSelector.of("photographerAge", WithinPredicate.on(List.of(55, 22))))
                .properties(List.of("photographerName", "photographerAge"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Jack"), photographer.getProperties().get("photographerName"));
    }

    @Test
    @DisplayName("should project nodes that have a property value (string without)")
    void testProjectNodesByStringWithout() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new LabelSelector("Photographer"))
                .filter(HasPropertyValueSelector.of("photographerName", WithoutPredicate.on(List.of("Bob", "Bill"))))
                .properties(List.of("photographerName"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Jack"), photographer.getProperties().get("photographerName"));
    }

    @Test
    @DisplayName("should project nodes that have a property value (number without)")
    void testProjectNodesByNumberWithout() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new LabelSelector("Photographer"))
                .filter(HasPropertyValueSelector.of("photographerAge", WithoutPredicate.on(List.of(55, 22))))
                .properties(List.of("photographerName", "photographerAge"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Bill"), photographer.getProperties().get("photographerName"));
    }

}

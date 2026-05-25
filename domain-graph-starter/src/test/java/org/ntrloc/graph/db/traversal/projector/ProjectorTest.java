package org.ntrloc.graph.db.traversal.projector;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.ItemStatus;
import org.ntrloc.graph.db.LabelConstants;
import org.ntrloc.graph.db.PropertyConstants;
import org.ntrloc.graph.db.language.projection.AllLinksProjectionSpec;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.LinkProjection;
import org.ntrloc.graph.db.language.projection.LinkProjectionSpec;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.language.projection.SpecificLinksProjectionSpec;
import org.ntrloc.graph.db.language.selectors.HasPropertyValueSelector;
import org.ntrloc.graph.db.language.selectors.ItemTypeSelector;
import org.ntrloc.graph.db.language.selectors.predicate.EqualsPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.GreaterThanPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.LessThanPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.NotEqualsPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.WithinPredicate;
import org.ntrloc.graph.db.language.selectors.predicate.WithoutPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        traversalSource.tx().begin();
        traversalSource
                // entity nodes
                .addV("Photo")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "p1")
                    .property(PropertyConstants.ITEM_TYPE_PROPERTY, "Photo")
                    .property(PropertyConstants.VERSION_PROPERTY, 1)
                    .property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, true)
                    .property("photoName", "photo1")
                    .property("photoColorspace", "B&W")
                    .as("photo1")

                .addV("Photo")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "p2")
                    .property(PropertyConstants.ITEM_TYPE_PROPERTY, "Photo")
                    .property(PropertyConstants.VERSION_PROPERTY, 1)
                    .property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, true)
                    .property("photoName", "photo2")
                    .property("photoColorspace", "RGB")
                    .as("photo2")

                .addV("Photographer")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "ph3")
                    .property(PropertyConstants.ITEM_TYPE_PROPERTY, "Photographer")
                    .property(PropertyConstants.VERSION_PROPERTY, 1)
                    .property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, true)
                    .property("photographerName", "Bill")
                    .property("photographerAge", 30)
                    .as("photographer1")

                .addV("Photographer")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "ph4")
                    .property(PropertyConstants.ITEM_TYPE_PROPERTY, "Photographer")
                    .property(PropertyConstants.VERSION_PROPERTY, 1)
                    .property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, true)
                    .property("photographerName", "Jack")
                    .property("photographerAge", 55)
                    .as("photographer2")

                .addV("Lightbox")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "lb1")
                    .property(PropertyConstants.ITEM_TYPE_PROPERTY, "Lightbox")
                    .property(PropertyConstants.VERSION_PROPERTY, 1)
                    .property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, true)
                    .property("lightboxName", "lightbox1")
                    .as("lb1")

                .addV("Agency")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "a3")
                    .property(PropertyConstants.ITEM_TYPE_PROPERTY, "Agency")
                    .property(PropertyConstants.VERSION_PROPERTY, 1)
                    .property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, true)
                    .property("agencyName", "Some Agency")
                    .as("a3")

                // link node
                .addV("photoCreatedId")
                    .property("date", "2020-01-01")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "created1")
                    .property(PropertyConstants.LINK_TYPE_PROPERTY, "photoCreatedId")
                    .property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, true)
                    .as("createdProp1")
                .addV("photoCreatedId")
                    .property("date", "2025-01-01")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "created2")
                    .property(PropertyConstants.LINK_TYPE_PROPERTY, "photoCreatedId")
                    .property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, true)
                    .as("createdProp2")
                .addV("photoCreatedId")
                    .property("date", "2025-09-01")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "created3")
                    .property(PropertyConstants.LINK_TYPE_PROPERTY, "photoCreatedId")
                    .property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, true)
                    .as("createdProp3")
                .addV("agencyEmploysId")
                    .property("hireDate", "April 2023")
                    .property(PropertyConstants.UNIQUE_ID_PROPERTY, "employs1")
                    .property(PropertyConstants.LINK_TYPE_PROPERTY, "agencyEmploysId")
                    .property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, true)
                    .as("employsProp1")

                // connection from entity->link->entity
                .addE("photoCreatedId-in").from("photographer1").to("createdProp1")
                .addE("photoCreatedId-out").from("createdProp1").to("photo1")
                .addE("photoCreatedId-in").from("photographer2").to("createdProp2")
                .addE("photoCreatedId-out").from("createdProp2").to("photo2")
                .addE("photoCreatedId-in").from("photographer1").to("createdProp3")
                .addE("photoCreatedId-out").from("createdProp3").to("lb1")
                .addE("agencyEmploysId-in").from("a3").to("employsProp1")
                .addE("agencyEmploysId-out").from("employsProp1").to("photographer1")

                .iterate();
        traversalSource.tx().commit();
    }

    @Test
    @DisplayName("should execute a simple item projection")
    void testSimpleItemProjection() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photo"))
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
        SpecificLinksProjectionSpec linksSpec = new SpecificLinksProjectionSpec(Set.of(
                new LinkProjectionSpec("photoCreatedId", Direction.OUT).properties(List.of("date")),
                new LinkProjectionSpec("agencyEmploysId", Direction.IN)
        ));
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"))
                .properties(List.of("photographerName"))
                .link(linksSpec);
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(2, list.size());
        Optional<ItemProjection> photographer = list.stream().filter(p -> p.getLinks().get("photoCreatedId") != null && !p.getLinks().get("photoCreatedId").isEmpty() && p.getLinks().get("agencyEmploysId") != null && !p.getLinks().get("agencyEmploysId").isEmpty()).findFirst();
        assertTrue(photographer.isPresent());
    }

    @Test
    @DisplayName("should project nodes that have a property value (equals)")
    void testProjectNodesByEquals() {
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"))
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
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"))
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
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"))
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
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"))
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
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"))
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
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"))
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
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"))
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
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"))
                .filter(HasPropertyValueSelector.of("photographerAge", WithoutPredicate.on(List.of(55, 22))))
                .properties(List.of("photographerName", "photographerAge"));
        Iterable<ItemProjection> projections = projector.project(spec);
        List<ItemProjection> list = StreamSupport.stream(projections.spliterator(), false).toList();
        assertEquals(1, list.size());
        ItemProjection photographer = list.get(0);
        assertEquals(List.of("Bill"), photographer.getProperties().get("photographerName"));
    }

    @Test
    @DisplayName("should project nodes that have an uncommitted replacement version")
    void testProjectNodesByUncommittedVersion() {
        var photo1Id = traversalSource.V()
                .hasLabel("Photo")
                .has(PropertyConstants.UNIQUE_ID_PROPERTY, "p1").id().next();
        traversalSource.tx().begin();
        traversalSource.addV("Photo")
                .property(PropertyConstants.STATUS_PROPERTY, ItemStatus.UNCOMMITTED_CREATE.toString()).as("new")
                .addE(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).from("new").to(__.V(photo1Id)).iterate();
        traversalSource.tx().commit();
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photo"));
        List<ItemProjection> photos = projector.project(spec);
        assertEquals(2, photos.size());
    }

    @Test
    @DisplayName("should not project nodes that have a committed replacement version")
    void testDoNotProjectNodesWithReplacementVersion() {
        var photo1Id = traversalSource.V()
                .hasLabel("Photo")
                .has(PropertyConstants.UNIQUE_ID_PROPERTY, "p1").id().next();
        traversalSource.tx().begin();
        traversalSource.addV("Photo")
                .property(PropertyConstants.STATUS_PROPERTY, ItemStatus.NORMAL.toString()).as("new")
                .addE(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).from("new").to(__.V(photo1Id)).iterate();
        traversalSource.V(photo1Id).property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, false).iterate();
        traversalSource.tx().commit();
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photo"));
        List<ItemProjection> photos = projector.project(spec);
        assertEquals(1, photos.size());
    }

    @Test
    @DisplayName("should project links that have an uncommitted replacement version")
    void testProjectLinksByUncommittedVersion() {
        var employsLinkId = traversalSource.V()
                .hasLabel("agencyEmploysId").id().next();
        traversalSource.tx().begin();
        traversalSource.addV("agencyEmploysId")
                .property(PropertyConstants.STATUS_PROPERTY, ItemStatus.UNCOMMITTED_UPDATE.toString()).as("new")
                .addE(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).from("new").to(__.V(employsLinkId)).iterate();
        traversalSource.tx().commit();
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Agency"));
        spec.setLinks(new AllLinksProjectionSpec());
        List<ItemProjection> agencies = projector.project(spec);
        ItemProjection agency = agencies.get(0);
        List<LinkProjection> photographerLinks = agency.getLinks().get("agencyEmploysId");
        assertNotNull(photographerLinks);
        assertEquals(1, photographerLinks.size());
    }

    @Test
    @DisplayName("should not project links that have a committed replacement version")
    void testProjectLinksByCommittedVersion() {
        var employsLinkId = traversalSource.V().hasLabel("agencyEmploysId").id().next();
        traversalSource.tx().begin();
        traversalSource.addV("agencyEmploysId")
                .property(PropertyConstants.STATUS_PROPERTY, ItemStatus.NORMAL.toString())
                .property(PropertyConstants.LINK_TYPE_PROPERTY, "agencyEmploysId")
                .as("new")
                .addE(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).from("new").to(__.V(employsLinkId)).iterate();
        traversalSource.V(employsLinkId).property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, false).iterate();
        traversalSource.tx().commit();
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Agency"));
        spec.setLinks(new AllLinksProjectionSpec());
        List<ItemProjection> agencies = projector.project(spec);
        ItemProjection agency = agencies.get(0);
        List<LinkProjection> photographerLinks = agency.getLinks().get("agencyEmploysId");
        assertNull(photographerLinks);
    }

    @Test
    @DisplayName("should project links to target nodes that have an uncommitted replacement version")
    void testProjectLinksToTargetsByUncommittedVersion() {
        var photographerId = traversalSource.V()
                .hasLabel("Photographer").has(PropertyConstants.UNIQUE_ID_PROPERTY, "ph3").id().next();
        traversalSource.tx().begin();
        traversalSource.addV("Photographer")
                .property(PropertyConstants.STATUS_PROPERTY, ItemStatus.UNCOMMITTED_UPDATE.toString()).as("new")
                .addE(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).from("new").to(__.V(photographerId)).iterate();
        traversalSource.tx().commit();
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Agency"));
        spec.setLinks(new AllLinksProjectionSpec());
        List<ItemProjection> agencies = projector.project(spec);
        ItemProjection agency = agencies.get(0);
        List<LinkProjection> photographerLinks = agency.getLinks().get("agencyEmploysId");
        assertNotNull(photographerLinks);
        assertEquals(1, photographerLinks.size());
    }

    @Test
    @DisplayName("should not project links to target nodes that have a committed, unlinked replacement version")
    void testNoProjectLinksOnUncommittedUnlinkedNodes() {
        var photographerId = traversalSource.V()
                .hasLabel("Photographer").has(PropertyConstants.UNIQUE_ID_PROPERTY, "ph3").id().next();
        traversalSource.tx().begin();
        // update the photographer but don't link it to the agency
        traversalSource.addV("Photographer")
                .property(PropertyConstants.STATUS_PROPERTY, ItemStatus.NORMAL.toString()).as("new")
                .addE(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).from("new").to(__.V(photographerId)).iterate();
        traversalSource.V(photographerId).property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, false).iterate();
        traversalSource.tx().commit();
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Agency"));
        spec.setLinks(new AllLinksProjectionSpec());
        List<ItemProjection> agencies = projector.project(spec);
        ItemProjection agency = agencies.get(0);
        List<LinkProjection> photographerLinks = agency.getLinks().get("agencyEmploysId");
        assertNull(photographerLinks);
    }

    @Test
    @DisplayName("should project links to target nodes that have a committed, linked replacement version")
    void testProjectLinksToTargetsByCommittedLinkedVersion() {
        var photographerId = traversalSource.V()
                .hasLabel("Photographer").has(PropertyConstants.UNIQUE_ID_PROPERTY, "ph3").id().next();
        var agencyLinkId = traversalSource.V().hasLabel("agencyEmploysId").id().next();

        traversalSource.tx().begin();
        traversalSource.addV("Photographer")
                .property(PropertyConstants.STATUS_PROPERTY, ItemStatus.NORMAL.toString()).as("new")
                .property(PropertyConstants.VERSION_PROPERTY, 2)
                .property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, true)
                .addE(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).from("new").to(__.V(photographerId))
                .addE("agencyEmploysId-out").from(__.V(agencyLinkId)).to("new")
                .iterate();
        traversalSource.V(photographerId).property(PropertyConstants.IS_LATEST_VERSION_PROPERTY, false).iterate();
        traversalSource.tx().commit();
        Projector projector = new Projector(traversalSource);
        SelectableItemProjectionSpec spec = new SelectableItemProjectionSpec(new ItemTypeSelector("Agency"));
        spec.setLinks(new AllLinksProjectionSpec());
        List<ItemProjection> agencies = projector.project(spec);
        ItemProjection agency = agencies.get(0);
        List<LinkProjection> photographerLinks = agency.getLinks().get("agencyEmploysId");
        assertNotNull(photographerLinks);
        assertEquals(1, photographerLinks.size());
    }

}

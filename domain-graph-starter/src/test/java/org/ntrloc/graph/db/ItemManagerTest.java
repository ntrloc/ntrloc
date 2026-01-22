package org.ntrloc.graph.db;

import com.hazelcast.map.IMap;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.impl.ItemManagerImpl;
import org.ntrloc.graph.db.impl.ItemManagerSchemaNameIdTranslator;
import org.ntrloc.graph.db.language.BooleanProperty;
import org.ntrloc.graph.db.language.DateProperty;
import org.ntrloc.graph.db.language.IntProperty;
import org.ntrloc.graph.db.language.StringProperty;
import org.ntrloc.graph.db.language.mutation.ItemCreateMutation;
import org.ntrloc.graph.db.language.mutation.ItemDeleteMutation;
import org.ntrloc.graph.db.language.mutation.ItemUpdateMutation;
import org.ntrloc.graph.db.language.mutation.LinkCreateMutation;
import org.ntrloc.graph.db.language.mutation.LinkUpdateMutation;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.language.projection.AllLinksProjectionSpec;
import org.ntrloc.graph.db.language.projection.IncomingLinkProjection;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.ItemProjectionSpec;
import org.ntrloc.graph.db.language.projection.LinkProjection;
import org.ntrloc.graph.db.language.projection.LinkProjectionSpec;
import org.ntrloc.graph.db.language.projection.OutgoingLinkProjection;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.language.projection.SpecificLinksProjectionSpec;
import org.ntrloc.graph.db.language.selectors.IdSelector;
import org.ntrloc.graph.db.language.selectors.ItemTypeSelector;
import org.ntrloc.graph.db.schema.Cardinality;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.db.schema.impl.SchemaManagerImpl;
import org.ntrloc.graph.db.storage.BinaryStorageAdapter;
import org.ntrloc.graph.db.storage.BinaryStorageAdapterConfiguration;
import org.ntrloc.graph.db.storage.impl.BlockDeviceBinaryStorageAdapter;
import org.ntrloc.graph.db.traversal.projector.Projector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.ntrloc.graph.db.PropertyConstants.ITEM_TYPE_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.UNIQUE_ID_PROPERTY;

@DisplayName("An item manager")
class ItemManagerTest {

    private static final Logger LOG = LoggerFactory.getLogger(ItemManagerTest.class);

    private GraphTraversalSource traversalSource;
    private JanusGraph janusGraph;

    private SchemaManager schemaManager;
    private ItemManager itemManager;

    @BeforeEach
    void init() throws IOException {
        if (janusGraph != null && janusGraph.isOpen()) {
            try {
                traversalSource.V().drop().next();
            } catch (NoSuchElementException nee) {
            }

            try {
                traversalSource.E().drop().next();
            } catch (NoSuchElementException nee) {

            }

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

        BinaryStorageAdapterConfiguration storageAdapterConfiguration = new BinaryStorageAdapterConfiguration();
        storageAdapterConfiguration.setAutocreate(true);
        storageAdapterConfiguration.setLocation("target/itemmanager/storage");
        BinaryStorageAdapter adapter = new BlockDeviceBinaryStorageAdapter(storageAdapterConfiguration);
        ClusterService clusterService = mock(ClusterService.class);

        doReturn(mock(IMap.class)).when(clusterService).getMap(anyString());
        schemaManager = new SchemaManagerImpl(janusGraph, traversalSource, clusterService);
        Projector projector = new Projector(traversalSource);
        ItemManagerSchemaNameIdTranslator idCache = new ItemManagerSchemaNameIdTranslator(schemaManager);
        itemManager = new ItemManagerImpl(traversalSource, adapter, idCache, projector);
        setUpSchema();
    }

    private void setUpSchema() {
        ItemDefinition photoDefinition = new ItemDefinition();
        photoDefinition.setName("Photo");

        PropertyDefinition nameDefinition = new PropertyDefinition("name", PropertyType.STRING, "");
        PropertyDefinition numberDefinition = new PropertyDefinition("number", PropertyType.INT, "");
        PropertyDefinition booleanDefinition = new PropertyDefinition("boolean", PropertyType.BOOLEAN, "");
        PropertyDefinition dateDefinition = new PropertyDefinition("someDate", PropertyType.DATE, "");
        PropertyDefinition doublePropertyDefinition = new PropertyDefinition("double", PropertyType.DOUBLE, "");
        PropertyDefinition intListDefinition = new PropertyDefinition("intList", PropertyType.INT_LIST, "");
        photoDefinition.setProperties(Set.of(nameDefinition, numberDefinition, booleanDefinition, dateDefinition, doublePropertyDefinition, intListDefinition));

        schemaManager.createItemDefinition(photoDefinition);

        ItemDefinition photographerDefinition = new ItemDefinition();
        photographerDefinition.setName("Photographer");
        PropertyDefinition photographerNameDefinition = new PropertyDefinition("name", PropertyType.STRING, "");
        photographerDefinition.setProperties(Set.of(photographerNameDefinition));
        schemaManager.createItemDefinition(photographerDefinition);

        ItemDefinition agencyDefinition = new ItemDefinition();
        agencyDefinition.setName("Agency");
        PropertyDefinition agencyNameDefinition = new PropertyDefinition("name", PropertyType.STRING, "");
        agencyDefinition.setProperties(Set.of(agencyNameDefinition));
        schemaManager.createItemDefinition(agencyDefinition);

        LinkDefinition photographerCreatedPhotoLink = new LinkDefinition();
        photographerCreatedPhotoLink.setSourceLabel("created");
        photographerCreatedPhotoLink.setTargetLabel("createdBy");
        photographerCreatedPhotoLink.setSourceItemType(photographerDefinition.getName());
        photographerCreatedPhotoLink.setTargetItemType(photoDefinition.getName());
        photographerCreatedPhotoLink.setSourceCardinality(new Cardinality(1, 1));
        photographerCreatedPhotoLink.setSourceVersionAction(LinkDefinition.VersionAction.COPY);
        photographerCreatedPhotoLink.setTargetCardinality(new Cardinality(0, null));
        photographerCreatedPhotoLink.setTargetVersionAction(LinkDefinition.VersionAction.MOVE);
        PropertyDefinition createdDateDefinition = new PropertyDefinition("createdDate", PropertyType.DATE, "Photo date created");
        photographerCreatedPhotoLink.setProperties(Set.of(createdDateDefinition));
        schemaManager.createLinkDefinition(photographerCreatedPhotoLink);

        LinkDefinition agencyEmploysPhotographerLink = new LinkDefinition();
        agencyEmploysPhotographerLink.setSourceLabel("employs");
        agencyEmploysPhotographerLink.setTargetLabel("worksFor");
        agencyEmploysPhotographerLink.setSourceItemType(agencyDefinition.getName());
        agencyEmploysPhotographerLink.setTargetItemType(photographerDefinition.getName());
        agencyEmploysPhotographerLink.setSourceCardinality(new Cardinality(1, 1));
        agencyEmploysPhotographerLink.setSourceVersionAction(LinkDefinition.VersionAction.COPY);
        agencyEmploysPhotographerLink.setTargetCardinality(new Cardinality(0, null));
        agencyEmploysPhotographerLink.setTargetVersionAction(LinkDefinition.VersionAction.MOVE);
        PropertyDefinition hiredDateDefinition = new PropertyDefinition("hireDate", PropertyType.DATE, "Photographer hire date");
        agencyEmploysPhotographerLink.setProperties(Set.of(hiredDateDefinition));
        schemaManager.createLinkDefinition(agencyEmploysPhotographerLink);
    }

    @Test
    @DisplayName("should capture the MIME type for an uploaded binary")
    void testCaptureMimeType() throws IOException {
        var files = Map.of("test.jpg", "image/jpeg", "test.png", "image/png");

        for (var entry: files.entrySet()) {
            var image = entry.getKey();
            var mimeType = entry.getValue();
            var inputStream = getClass().getClassLoader().getResourceAsStream(String.format("testImages/%s", image));
            var writer = itemManager.openWriter();
            writer.write(inputStream.readAllBytes());
            String dataNodeId = itemManager.commitBinary(writer);
            var dataNode = traversalSource.V().has(UNIQUE_ID_PROPERTY, dataNodeId).elementMap().next();
            assert(dataNode.containsKey("mimeType"));
            var recordedType = dataNode.get("mimeType");
            assertEquals(mimeType, recordedType);
        }
    }

    @Test
    @DisplayName("should create an item with properties")
    void testCreateItem() {
        ItemCreateMutation photoCreate = new ItemCreateMutation()
                .itemType("Photo")
                .properties(List.of(new StringProperty("name", "photo1")));

        MutationRequest req = new MutationRequest(List.of(photoCreate));
        var res = itemManager.executeMutation(req);
        assertEquals(1, res.getItemMutationResponses().size());
        var id = res.getItemMutationResponses().get(0).getId();
        assertNotNull(id, "null id returned");
        var iter = traversalSource.V().has(UNIQUE_ID_PROPERTY, id)
                .project("id", "label", "props")
                .by(__.id())
                .by(__.label())
                .by(__.valueMap());
        assertTrue(iter.hasNext(), "no graph item found");
        var item = iter.next();
        var props = (Map<String, Object>) item.get("props");

        Optional<ItemDefinition> defOpt = schemaManager.retrieveItemDefinition("Photo");
        assertTrue(defOpt.isPresent());
        ItemDefinition photoDef = defOpt.get();
        Optional<PropertyDefinition> nameOpt = photoDef.getProperties().stream().filter(p -> p.getName().equals("name")).findFirst();
        assertTrue(nameOpt.isPresent());
        PropertyDefinition nameDefinition = nameOpt.get();

        assertTrue(props.containsKey(nameDefinition.getUid()), "name not set");
        assertTrue(props.containsKey(UNIQUE_ID_PROPERTY), "uid not set");
        assertTrue(props.containsKey(ITEM_TYPE_PROPERTY), "type not set");

        SelectableItemProjectionSpec itemProjectionSpec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photo"));
        List<ItemProjection> projections = itemManager.executeProjection(itemProjectionSpec);
        assertEquals(1, projections.size());
        ItemProjection projection = projections.get(0);
        assertEquals("Photo", projection.getItemType());
        assertEquals("photo1", projection.getProperties().get("name"));
    }

    @Test
    @DisplayName("should delete an item")
    void testDeleteItem() {
        ItemCreateMutation photoCreate = new ItemCreateMutation()
                .itemType("Photo")
                .properties(List.of(new StringProperty("name", "photo1")));
        MutationRequest req = new MutationRequest(List.of(photoCreate));
        var res = itemManager.executeMutation(req);
        assertEquals(1, res.getItemMutationResponses().size());
        var id = res.getItemMutationResponses().get(0).getId();
        assertNotNull(id, "null id returned");
        ItemDeleteMutation delete = new ItemDeleteMutation(new IdSelector(id));
        MutationRequest deleteReq = new MutationRequest(List.of(delete));
        var deleteRes = itemManager.executeMutation(deleteReq);
        assertEquals(1, deleteRes.getItemMutationResponses().size(), "delete item count mismatch");
        assertEquals(id, deleteRes.getItemMutationResponses().get(0).getId(), "deleted item id mismatch");
        SelectableItemProjectionSpec projectionSpec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photo"));
        List<ItemProjection> projections = itemManager.executeProjection(projectionSpec);
        assertEquals(0, projections.size(), "projection count mismatch");
    }

    @Test
    @DisplayName("should create two items and link them from the source")
    void testCreateAndLinkItemsFromSource() {
        ItemCreateMutation photoCreate = new ItemCreateMutation()
                .itemType("Photo")
                .refId("photoRef")
                .properties(List.of(new StringProperty("name", "photo1")));

        ItemCreateMutation photographerCreate = new ItemCreateMutation()
                .itemType("Photographer")
                .properties(List.of( new StringProperty("name", "photographer1")));

        LinkCreateMutation linkCreate = new LinkCreateMutation()
                .selector(new IdSelector(photoCreate.getRefId(), IdSelector.Scope.LOCAL))
                .linkType("created")
                .properties(List.of(new DateProperty("createdDate", "2022-01-01")));

        photographerCreate.setLinks(List.of(linkCreate));

        MutationRequest req = new MutationRequest(List.of(photoCreate, photographerCreate));
        var res = itemManager.executeMutation(req);
        assertEquals(2, res.getItemMutationResponses().size());
        assertEquals(1, res.getLinkMutationResponses().size());

        SelectableItemProjectionSpec itemProjectionSpec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"));
        itemProjectionSpec.setLinks(new SpecificLinksProjectionSpec(Set.of(new LinkProjectionSpec("created", Direction.OUT, new ItemProjectionSpec().properties(List.of("name"))))));
        List<ItemProjection> projections = itemManager.executeProjection(itemProjectionSpec);
        assertEquals(1, projections.size());
        ItemProjection projection = projections.get(0);
        assertEquals("Photographer", projection.getItemType());
        assertEquals("photographer1", projection.getProperties().get("name"));
        OutgoingLinkProjection createdLink = (OutgoingLinkProjection) projection.getLinks().get("created").get(0);
        assertEquals("2022-01-01", createdLink.getProperties().get("createdDate"));
        ItemProjection photoProjection = createdLink.getTarget();
        assertEquals("Photo", photoProjection.getItemType());
        assertEquals("photo1", photoProjection.getProperties().get("name"));
    }

    @Test
    @DisplayName("should create two items and link them from the target")
    void testCreateAndLinkItemsFromTarget() {
        ItemCreateMutation photoCreate = new ItemCreateMutation()
                .itemType("Photo")
                .refId("photoRef")
                .properties(List.of(new StringProperty("name", "photo1")));

        ItemCreateMutation photographerCreate = new ItemCreateMutation()
                .itemType("Photographer")
                .refId("photographerRef")
                .properties(List.of( new StringProperty("name", "photographer1")));

        LinkCreateMutation linkCreate = new LinkCreateMutation()
                .selector(new IdSelector(photographerCreate.getRefId(), IdSelector.Scope.LOCAL))
                .linkType("createdBy")
                .properties(List.of(new DateProperty("createdDate", "2022-01-01")));

        photoCreate.setLinks(List.of(linkCreate));

        MutationRequest req = new MutationRequest(List.of(photoCreate, photographerCreate));
        var res = itemManager.executeMutation(req);
        assertEquals(2, res.getItemMutationResponses().size());
        assertEquals(1, res.getLinkMutationResponses().size());

        SelectableItemProjectionSpec itemProjectionSpec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photo"));
        itemProjectionSpec.setLinks(new AllLinksProjectionSpec());
        List<ItemProjection> projections = itemManager.executeProjection(itemProjectionSpec);
        assertEquals(1, projections.size());
        ItemProjection projection = projections.get(0);
        assertEquals("Photo", projection.getItemType());
        IncomingLinkProjection creatorLink = (IncomingLinkProjection) projection.getLinks().get("createdBy").get(0);
        assertEquals("2022-01-01", creatorLink.getProperties().get("createdDate"));
    }

    @Test
    @DisplayName("should update an item")
    void testUpdateItem() {
        ItemCreateMutation photoCreate = new ItemCreateMutation()
                .itemType("Photo")
                .properties(List.of(
                new StringProperty("name", "photo1"),
                new IntProperty("number", 3)
                ));

        MutationRequest req = new MutationRequest(List.of(photoCreate));
        var res = itemManager.executeMutation(req);
        assertEquals(1, res.getItemMutationResponses().size());
        var id = res.getItemMutationResponses().get(0).getId();

        ItemUpdateMutation photoUpdate = new ItemUpdateMutation()
        .itemType("Photo")
                .id(id)
                .properties(List.of(
                    new StringProperty("name", "photo1b"), // change name
                    new IntProperty("number", null), // remove number
                    new BooleanProperty("boolean", false) // add boolean
                ));
        MutationRequest updateReq = new MutationRequest(List.of(photoUpdate));
        var res2 = itemManager.executeMutation(updateReq);
        assertEquals(1, res2.getItemMutationResponses().size());

        SelectableItemProjectionSpec itemProjectionSpec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photo"));
        List<ItemProjection> projections = itemManager.executeProjection(itemProjectionSpec);
        assertEquals(1, projections.size());
        ItemProjection projection = projections.get(0);
        Map<String, Object> props = projection.getProperties();
        assertEquals("photo1b", props.get("name"));
        assertEquals(false, props.get("boolean"));
        assertNull(props.get("number"));
    }

    @Test
    @DisplayName("should maintain links from a source item when it is versioned")
    void testMaintainOutboundLinks() {
        ItemCreateMutation photographerCreate = new ItemCreateMutation()
                .itemType("Photographer")
                .properties(List.of(new StringProperty("name", "photographer1")))
                .refId("photographerRef");

        ItemCreateMutation agencyCreate = new ItemCreateMutation()
                .itemType("Agency")
                .properties(List.of(new StringProperty("name", "agency a")));

        LinkCreateMutation agencyEmploysPhotographerLink = new LinkCreateMutation()
                .selector(new IdSelector(photographerCreate.getRefId(), IdSelector.Scope.LOCAL))
                .linkType("employs")
                .properties(List.of(new DateProperty("hireDate", "2022-01-01")));

        agencyCreate.setLinks(List.of(agencyEmploysPhotographerLink));

        MutationRequest req = new MutationRequest(List.of(photographerCreate, agencyCreate));
        itemManager.executeMutation(req);

        SelectableItemProjectionSpec apencySpec = new SelectableItemProjectionSpec(new ItemTypeSelector("Agency"));
        apencySpec.setLinks(new AllLinksProjectionSpec());
        List<ItemProjection> agencies = itemManager.executeProjection(apencySpec);
        ItemProjection agency = agencies.get(0);
        Map<String, List<LinkProjection>> links = agency.getLinks();
        assertTrue(links.containsKey("employs"));

        // update the agency (the source item)
        ItemUpdateMutation agencyUpdate = new ItemUpdateMutation()
                .itemType("Agency")
                .id(agency.getId())
                .properties(List.of( new StringProperty("name", "agency b")));

        MutationRequest updateReq = new MutationRequest(List.of(agencyUpdate));
        itemManager.executeMutation(updateReq);

        // check that the agency is still linked to the photographer
        agencies = itemManager.executeProjection(apencySpec);
        agency = agencies.get(0);
        assertEquals(2, agency.getVersion());
        links = agency.getLinks();
        assertTrue(links.containsKey("employs"));

        // check that the photographer is only linked to the new version of the agency
        SelectableItemProjectionSpec photographerSpec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"));
        photographerSpec.setLinks(new AllLinksProjectionSpec());
        List<ItemProjection> photographers = itemManager.executeProjection(photographerSpec);
        ItemProjection photographer = photographers.get(0);
        Map<String, List<LinkProjection>> photographerLinks = photographer.getLinks();
        assertTrue(photographerLinks.containsKey("worksFor"));
        assertEquals(1, photographerLinks.get("worksFor").size());
        IncomingLinkProjection agencyProj = (IncomingLinkProjection) photographerLinks.get("worksFor").get(0);
        assertEquals(2, agencyProj.getSource().getVersion());
    }

    @Test
    @DisplayName("should maintain links to a target item when it is versioned")
    void testMaintainInboundLinks() {
        ItemCreateMutation photographerCreate = new ItemCreateMutation()
                .itemType("Photographer")
                .properties(List.of(new StringProperty("name", "photographer1")))
                .refId("photographerRef");

        ItemCreateMutation agencyCreate = new ItemCreateMutation()
                .itemType("Agency")
                .properties(List.of(new StringProperty("name", "agency a")));

        LinkCreateMutation agencyEmploysPhotographerLink = new LinkCreateMutation()
                .selector(new IdSelector(photographerCreate.getRefId(), IdSelector.Scope.LOCAL))
                .linkType("employs")
                .properties(List.of(new DateProperty("hireDate", "2022-01-01")));

        agencyCreate.setLinks(List.of(agencyEmploysPhotographerLink));

        MutationRequest req = new MutationRequest(List.of(photographerCreate, agencyCreate));
        itemManager.executeMutation(req);

        SelectableItemProjectionSpec photographerSpec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"));
        photographerSpec.setLinks(new AllLinksProjectionSpec());
        List<ItemProjection> photographers = itemManager.executeProjection(photographerSpec);
        ItemProjection photographer = photographers.get(0);
        Map<String, List<LinkProjection>> photographerLinks = photographer.getLinks();
        assertTrue(photographerLinks.containsKey("worksFor"));

        // update the photographer (the target item)
        ItemUpdateMutation photographerUpdate = new ItemUpdateMutation()
                .itemType("Photographer")
                .id(photographer.getId())
                .properties(List.of( new StringProperty("name", "photographer 2")));

        MutationRequest updateReq = new MutationRequest(List.of(photographerUpdate));
        itemManager.executeMutation(updateReq);

        // check that the photographer is still linked to the agency
        photographers = itemManager.executeProjection(photographerSpec);
        photographer = photographers.get(0);
        assertEquals(2, photographer.getVersion());
        Map<String, List<LinkProjection>> links = photographer.getLinks();
        assertTrue(links.containsKey("worksFor"));

        // check that the agency is only linked to the new version of the photographer

        SelectableItemProjectionSpec apencySpec = new SelectableItemProjectionSpec(new ItemTypeSelector("Agency"));
        apencySpec.setLinks(new AllLinksProjectionSpec());
        List<ItemProjection> agencies = itemManager.executeProjection(apencySpec);
        ItemProjection agency = agencies.get(0);
        links = agency.getLinks();
        assertTrue(links.containsKey("employs"));
        assertEquals(1, links.get("employs").size());
        OutgoingLinkProjection photographerProj = (OutgoingLinkProjection) links.get("employs").get(0);
        assertEquals(2, photographerProj.getTarget().getVersion());
    }

    @Test
    @DisplayName("should update a link")
    void testUpdateLink() {
        ItemCreateMutation photoCreate = new ItemCreateMutation()
                .itemType("Photo")
                .refId("photoRef")
                .properties(List.of(new StringProperty("name", "photo1")));

        ItemCreateMutation photographerCreate = new ItemCreateMutation()
                .itemType("Photographer")
                .properties(List.of( new StringProperty("name", "photographer1")));

        LinkCreateMutation linkCreate = new LinkCreateMutation()
                .selector(new IdSelector(photoCreate.getRefId(), IdSelector.Scope.LOCAL))
                .linkType("created")
                .properties(List.of(new DateProperty("createdDate", "2022-01-01")));

        photographerCreate.setLinks(List.of(linkCreate));

        MutationRequest req = new MutationRequest(List.of(photoCreate, photographerCreate));
        itemManager.executeMutation(req);

        SelectableItemProjectionSpec itemProjectionSpec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"));
        itemProjectionSpec.setLinks(new AllLinksProjectionSpec());
        List<ItemProjection> photographers = itemManager.executeProjection(itemProjectionSpec);
        ItemProjection photographer = photographers.get(0);
        LinkProjection link = photographer.getLinks().get("created").get(0);

        LinkUpdateMutation linkUpdate = new LinkUpdateMutation().linkType("created").selector(new IdSelector(link.getId()));
        linkUpdate.setProperties(List.of(new DateProperty("createdDate", "2022-02-01")));

        ItemUpdateMutation photographerUpdate = new ItemUpdateMutation()
                .itemType("Photographer")
                .id(photographer.getId())
                .links(List.of(linkUpdate));

        MutationRequest updateReq = new MutationRequest(List.of(photographerUpdate));
        itemManager.executeMutation(updateReq);

        photographers = itemManager.executeProjection(itemProjectionSpec);
        photographer = photographers.get(0);
        link = photographer.getLinks().get("created").get(0);
        assertEquals("2022-02-01", link.getProperties().get("createdDate"));
    }

    @Test
    @DisplayName("should update a source item and link")
    void testUpdateSourceAndLink() {
        ItemCreateMutation photoCreate = new ItemCreateMutation()
                .itemType("Photo")
                .refId("photoRef")
                .properties(List.of(new StringProperty("name", "photo1")));

        ItemCreateMutation photographerCreate = new ItemCreateMutation()
                .itemType("Photographer")
                .properties(List.of( new StringProperty("name", "photographer1")));

        LinkCreateMutation linkCreate = new LinkCreateMutation()
                .selector(new IdSelector(photoCreate.getRefId(), IdSelector.Scope.LOCAL))
                .linkType("created")
                .properties(List.of(new DateProperty("createdDate", "2022-01-01")));

        photographerCreate.setLinks(List.of(linkCreate));

        MutationRequest req = new MutationRequest(List.of(photoCreate, photographerCreate));
        itemManager.executeMutation(req);

        SelectableItemProjectionSpec photographerSpec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer"));
        photographerSpec.setLinks(new AllLinksProjectionSpec());
        ItemProjection photographer = itemManager.executeProjection(photographerSpec).get(0);

        LinkProjection link = photographer.getLinks().get("created").get(0);

        LinkUpdateMutation linkUpdate = new LinkUpdateMutation().linkType("created").selector(new IdSelector(link.getId()));
        linkUpdate.setProperties(List.of(new DateProperty("createdDate", "2022-02-01")));

        ItemUpdateMutation photographerUpdate = new ItemUpdateMutation()
                .itemType("Photographer")
                .properties(List.of(new StringProperty("name", "photographer2")))
                .id(photographer.getId())
                .links(List.of(linkUpdate));

        MutationRequest updateReq = new MutationRequest(List.of(photographerUpdate));
        itemManager.executeMutation(updateReq);

        photographer = itemManager.executeProjection(photographerSpec).get(0);
        assertEquals("photographer2", photographer.getProperties().get("name"));
        link = photographer.getLinks().get("created").get(0);
        assertEquals("2022-02-01", link.getProperties().get("createdDate"));

        ItemProjection photo = itemManager.executeProjection(new SelectableItemProjectionSpec(new ItemTypeSelector("Photo")).link(new AllLinksProjectionSpec())).get(0);
        photographer = ((IncomingLinkProjection)photo.getLinks().get("createdBy").get(0)).getSource();
        assertEquals("photographer2", photographer.getProperties().get("name"));
    }

    @Test
    @DisplayName("should update a target item and link")
    void testUpdateTargetAndLink() {
        ItemCreateMutation photoCreate = new ItemCreateMutation()
                .itemType("Photo")
                .refId("photoRef")
                .properties(List.of(new StringProperty("name", "photo1")));

        ItemCreateMutation photographerCreate = new ItemCreateMutation()
                .itemType("Photographer")
                .properties(List.of( new StringProperty("name", "photographer1")));

        LinkCreateMutation linkCreate = new LinkCreateMutation()
                .selector(new IdSelector(photoCreate.getRefId(), IdSelector.Scope.LOCAL))
                .linkType("created")
                .properties(List.of(new DateProperty("createdDate", "2022-01-01")));

        photographerCreate.setLinks(List.of(linkCreate));

        MutationRequest req = new MutationRequest(List.of(photoCreate, photographerCreate));
        itemManager.executeMutation(req);

        SelectableItemProjectionSpec photoSpec = new SelectableItemProjectionSpec(new ItemTypeSelector("Photo"));
        photoSpec.setLinks(new AllLinksProjectionSpec());
        List<ItemProjection> photos = itemManager.executeProjection(photoSpec);
        ItemProjection photo = photos.get(0);
        LinkProjection link = photo.getLinks().get("createdBy").get(0);

        LinkUpdateMutation linkUpdate = new LinkUpdateMutation().linkType("createdBy").selector(new IdSelector(link.getId()));
        linkUpdate.setProperties(List.of(new DateProperty("createdDate", "2022-02-01")));

        ItemUpdateMutation photoUpdate = new ItemUpdateMutation()
                .itemType("Photo")
                .properties(List.of(new StringProperty("name", "photo2")))
                .id(photo.getId())
                .links(List.of(linkUpdate));

        MutationRequest updateReq = new MutationRequest(List.of(photoUpdate));
        itemManager.executeMutation(updateReq);

        photos = itemManager.executeProjection(photoSpec);
        photo = photos.get(0);
        assertEquals("photo2", photo.getProperties().get("name"));
        link = photo.getLinks().get("createdBy").get(0);
        assertEquals("2022-02-01", link.getProperties().get("createdDate"));

        ItemProjection photographer = itemManager.executeProjection(new SelectableItemProjectionSpec(new ItemTypeSelector("Photographer")).link(new AllLinksProjectionSpec())).get(0);
        photo = ((OutgoingLinkProjection)photographer.getLinks().get("created").get(0)).getTarget();
        assertEquals("photo2", photo.getProperties().get("name"));
    }

}

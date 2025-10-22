package org.ntrloc.graph.db;

import com.hazelcast.map.IMap;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.impl.ItemManagerImpl;
import org.ntrloc.graph.db.language.DateProperty;
import org.ntrloc.graph.db.language.StringProperty;
import org.ntrloc.graph.db.language.mutation.ItemCreateMutation;
import org.ntrloc.graph.db.language.mutation.ItemDeleteMutation;
import org.ntrloc.graph.db.language.mutation.LinkCreateMutation;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.language.selectors.IdSelector;
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
import org.ntrloc.graph.db.traversal.mutator.Mutator;
import org.ntrloc.graph.db.traversal.mutator.impl.MutatorImpl;
import org.ntrloc.graph.db.traversal.projector.Projector;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.ntrloc.graph.db.PropertyConstants.ITEM_TYPE_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.LINK_TYPE_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.STATUS_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.UNIQUE_ID_PROPERTY;

@DisplayName("An item manager")
class ItemManagerMutationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ItemManagerMutationTest.class);

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
        Mutator mutator = new MutatorImpl(traversalSource);
        itemManager = new ItemManagerImpl(traversalSource, adapter, schemaManager, mutator, projector);
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

        LinkDefinition linkDefinition = new LinkDefinition();
        linkDefinition.setName("CREATED");
        linkDefinition.setSourceLabel("created");
        linkDefinition.setTargetLabel("createdBy");
        linkDefinition.setSourceItemType(photographerDefinition.getName());
        linkDefinition.setTargetItemType(photoDefinition.getName());
        linkDefinition.setSourceCardinality(new Cardinality(1, 1));
        linkDefinition.setSourceVersionAction(LinkDefinition.VersionAction.COPY);
        linkDefinition.setTargetCardinality(new Cardinality(0, null));
        linkDefinition.setTargetVersionAction(LinkDefinition.VersionAction.MOVE);
        PropertyDefinition createdDateDefinition = new PropertyDefinition("createdDate", PropertyType.DATE, "Photo date created");
        linkDefinition.setProperties(Set.of(createdDateDefinition));

        schemaManager.createLinkDefinition(linkDefinition);
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
        ItemCreateMutation photoCreate = new ItemCreateMutation();
        photoCreate.setEntityType("Photo");
        photoCreate.setProperties(List.of(
                new StringProperty("name", "photo1")
        ));
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

        String nameId = schemaManager.getItemPropertyId("Photo", "name");

        assertTrue(props.containsKey(nameId), "name not set");
        assertTrue(props.containsKey(UNIQUE_ID_PROPERTY), "uid not set");
        assertTrue(props.containsKey(ITEM_TYPE_PROPERTY), "type not set");
        assertEquals(ItemStatus.NORMAL.toString(), ((List)props.get(STATUS_PROPERTY)).get(0));
    }

    @Test
    @DisplayName("should delete an item")
    void testDeleteItem() {
        ItemCreateMutation photoCreate = new ItemCreateMutation();
        photoCreate.setEntityType("Photo");
        photoCreate.setProperties(List.of(
                new StringProperty("name", "photo1")
        ));
        MutationRequest req = new MutationRequest(List.of(photoCreate));
        var res = itemManager.executeMutation(req);
        assertEquals(1, res.getItemMutationResponses().size());
        var id = res.getItemMutationResponses().get(0).getId();
        assertNotNull(id, "null id returned");
        ItemDeleteMutation delete = new ItemDeleteMutation(id);
        MutationRequest deleteReq = new MutationRequest(List.of(delete));
        var deleteRes = itemManager.executeMutation(deleteReq);
        assertEquals(1, deleteRes.getItemMutationResponses().size(), "delete item count mismatch");
        assertEquals(id, deleteRes.getItemMutationResponses().get(0).getId(), "deleted item id mismatch");
    }

    @Test
    @DisplayName("should create two items and link them")
    void testCreateAndLinkItems() {
        ItemCreateMutation photoCreate = new ItemCreateMutation();
        photoCreate.setEntityType("Photo");
        photoCreate.setRefId("photoRef");
        photoCreate.setProperties(List.of(
                new StringProperty("name", "photo1")
        ));
        ItemCreateMutation photographerCreate = new ItemCreateMutation();
        photographerCreate.setEntityType("Photographer");
        photographerCreate.setProperties(List.of(
                new StringProperty("name", "photographer1")
        ));
        LinkCreateMutation linkCreate = new LinkCreateMutation();
        linkCreate.setLinkType("CREATED");
        IdSelector selector = new IdSelector(photoCreate.getRefId(), IdSelector.Type.LOCAL);
        linkCreate.setSelector(selector);
        photographerCreate.setLinks(List.of(linkCreate));

        MutationRequest req = new MutationRequest(List.of(photoCreate, photographerCreate));
        var res = itemManager.executeMutation(req);
        assertEquals(2, res.getItemMutationResponses().size());
        assertEquals(1, res.getLinkMutationResponses().size());

    }

    @Test
    @DisplayName("should label items and properties by ID")
    void testLabelItemsWithId() {
        ItemCreateMutation photoCreate = new ItemCreateMutation();
        photoCreate.setEntityType("Photo");
        photoCreate.setRefId("photoRef");
        photoCreate.setProperties(List.of(
                new StringProperty("name", "photo1")
        ));
        MutationRequest req = new MutationRequest(List.of(photoCreate));
        var res = itemManager.executeMutation(req);
        var createdPhoto = res.getItemMutationResponses().get(0);
        var createdPhotoType = createdPhoto.getItemType();
        assertEquals("Photo", createdPhotoType, "mutation item type mismatch");
        var photoUid = createdPhoto.getId();

        var photoTypeId = schemaManager.getItemTypeId("Photo");
        var photoNameId = schemaManager.getItemPropertyId("Photo", "name");

        var photoData = traversalSource.V().has(UNIQUE_ID_PROPERTY, photoUid).project("label", "props").by(__.label()).by(__.valueMap()).next();
        var photoLabel = (String) photoData.get("label");
        var photoProps = (Map<String, Object>) photoData.get("props");
        var itemType = ((List) photoProps.get(ITEM_TYPE_PROPERTY)).get(0);

        assertEquals(photoTypeId, photoLabel, "label mismatch");
        assertEquals(photoTypeId, itemType, "item type mismatch");
        try {
            assertTrue(photoProps.containsKey(photoNameId), "name not set");
        } catch (AssertionFailedError afe) {
            LOG.error("photo props: {}", photoProps);
            throw afe;
        }

    }

    @Test
    @DisplayName("should label links and properties by ID")
    void testLabelLinksWithId() {
        ItemCreateMutation photoCreate = new ItemCreateMutation();
        photoCreate.setEntityType("Photo");
        photoCreate.setRefId("photoRef");
        photoCreate.setProperties(List.of(
                new StringProperty("name", "photo1")
        ));
        ItemCreateMutation photographerCreate = new ItemCreateMutation();
        photographerCreate.setEntityType("Photographer");
        photographerCreate.setProperties(List.of(
                new StringProperty("name", "photographer1")
        ));
        LinkCreateMutation linkCreate = new LinkCreateMutation();
        linkCreate.setLinkType("CREATED");
        linkCreate.setProperties(List.of(new DateProperty("createdDate", new Date())));
        IdSelector selector = new IdSelector(photoCreate.getRefId(), IdSelector.Type.LOCAL);
        linkCreate.setSelector(selector);
        photographerCreate.setLinks(List.of(linkCreate));

        MutationRequest req = new MutationRequest(List.of(photoCreate, photographerCreate));
        var res = itemManager.executeMutation(req);

        var linkTypeId = schemaManager.getLinkTypeId("CREATED");
        var linkCreatedId = schemaManager.getLinkPropertyId("CREATED", "createdDate");

        var createdLinkMutation = res.getLinkMutationResponses().get(0);
        var createdLinkId = createdLinkMutation.getLinkId();
        var createdLinkType = createdLinkMutation.getLinkType();
        assertEquals("CREATED", createdLinkType, "mutation link type mismatch");
        var linkData = traversalSource.V().has(UNIQUE_ID_PROPERTY, createdLinkId).project("label", "props").by(__.label()).by(__.valueMap()).next();

        var linkLabel = (String) linkData.get("label");
        var linkProps = (Map<String, Object>) linkData.get("props");
        var linkType = ((List) linkProps.get(LINK_TYPE_PROPERTY)).get(0);

        assertEquals(linkTypeId, linkLabel, "label mismatch");
        assertEquals(linkTypeId, linkType, "item type mismatch");
        try {
            assertTrue(linkProps.containsKey(linkCreatedId), "created date not set");
        } catch (AssertionFailedError afe) {
            LOG.error("photo props: {}", linkProps);
            throw afe;
        }

    }

}

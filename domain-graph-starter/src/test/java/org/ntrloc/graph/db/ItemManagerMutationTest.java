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
import org.ntrloc.graph.db.traversal.mutator.Mutator;
import org.ntrloc.graph.db.traversal.mutator.impl.MutatorImpl;
import org.ntrloc.graph.db.traversal.projector.Projector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
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

        BinaryStorageAdapter adapter = mock(BinaryStorageAdapter.class);
        ClusterService clusterService = mock(ClusterService.class);
        Projector projector = new Projector(traversalSource);
        doReturn(mock(IMap.class)).when(clusterService).getMap(anyString());
        schemaManager = new SchemaManagerImpl(janusGraph, traversalSource, clusterService);
        Mutator mutator = new MutatorImpl(schemaManager, traversalSource);
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
        schemaManager.createLinkDefinition(linkDefinition);
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

}

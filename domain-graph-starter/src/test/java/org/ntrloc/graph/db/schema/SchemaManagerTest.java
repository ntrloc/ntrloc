package org.ntrloc.graph.db.schema;

import com.hazelcast.map.IMap;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.schema.impl.SchemaManagerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class SchemaManagerTest {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaManagerTest.class);

    private JanusGraph janusGraph;
    private GraphTraversalSource traversalSource;
    private SchemaManager schemaManager;

    @AfterEach
    void tearDown() {
        if (janusGraph != null && janusGraph.isOpen()) {
            janusGraph.close();
        }
    }

    @BeforeEach
    void setup() {
        janusGraph = JanusGraphFactory.build()
                .set("storage.backend", "inmemory")
                .set("cache.tx-cache-size", 0)
                .open();
        traversalSource = janusGraph.traversal();
        ClusterService clusterService = mock(ClusterService.class);
        doReturn(mock(IMap.class)).when(clusterService).getMap(anyString());
        schemaManager = new SchemaManagerImpl(new GraphSchemaBackend() {
            @Override public void ensureGlobalSchema() {}
            @Override public void createItemTypeSchema(String itemTypeUid, Map<String, PropertyType> propertiesByUid) {}
        }, traversalSource, clusterService);
    }

    @Test
    @DisplayName("create and retrieve entity definition")
    void testCreateItemDefinition() {
        ItemDefinition itemDefinition = new ItemDefinition();
        itemDefinition.setName("Product");

        PropertyDefinition isbnPropertyDefinition = new PropertyDefinition();
        isbnPropertyDefinition.setName("ISBN");
        isbnPropertyDefinition.setType(PropertyType.STRING);

        PropertyDefinition titlePropertyDefinition = new PropertyDefinition();
        titlePropertyDefinition.setName("Title");
        titlePropertyDefinition.setType(PropertyType.STRING);

        PropertyGroupDefinition levelGroupDefinition = new PropertyGroupDefinition();
        levelGroupDefinition.setName("Levels");

        PropertyDefinition graLevelDefinition = new PropertyDefinition();
        graLevelDefinition.setName("GRA");
        graLevelDefinition.setType(PropertyType.STRING);
        levelGroupDefinition.setProperties(Set.of(graLevelDefinition));
        itemDefinition.setPropertyGroups(Set.of(levelGroupDefinition));

        itemDefinition.setProperties(Set.of(isbnPropertyDefinition, titlePropertyDefinition));
        schemaManager.createItemDefinition(itemDefinition);

        Set<ItemDefinition> definitions = schemaManager.retrieveItemDefinitions();
        assertEquals(1, definitions.size(), "Incorrect number of entity definitions");

        Optional<ItemDefinition> schemaOpt = schemaManager.retrieveItemDefinition("Product");
        assertTrue(schemaOpt.isPresent(), "Schema not returned");
        assertEquals(itemDefinition, schemaOpt.get());
    }

    @Test
    @DisplayName("prepare the system to work with a new entity type")
    void testInitializeEntityDefinition() {
        ItemDefinition itemDefinition = new ItemDefinition();
        itemDefinition.setName("Product");

        PropertyDefinition isbnPropertyDefinition = new PropertyDefinition();
        isbnPropertyDefinition.setName("ISBN");
        isbnPropertyDefinition.setType(PropertyType.STRING);

        PropertyGroupDefinition levelGroupDefinition = new PropertyGroupDefinition();
        levelGroupDefinition.setName("Levels");

        PropertyDefinition graLevelDefinition = new PropertyDefinition();
        graLevelDefinition.setName("GRA");
        graLevelDefinition.setType(PropertyType.STRING);

        levelGroupDefinition.setProperties(Set.of(graLevelDefinition));
        itemDefinition.setPropertyGroups(Set.of(levelGroupDefinition));

        itemDefinition.setProperties(Set.of(isbnPropertyDefinition));
        schemaManager.createItemDefinition(itemDefinition);

        Optional<ItemDefinition> retrieved = schemaManager.retrieveItemDefinition("Product");
        assertTrue(retrieved.isPresent(), "item definition should be present");

        ItemDefinition def = retrieved.get();
        assertNotNull(def.getUid(), "uid should not be null");

        assertTrue(def.getProperties().stream().anyMatch(p -> p.getName().equals("ISBN")), "ISBN property should be present");
        assertTrue(def.getPropertyGroups().stream()
                .flatMap(g -> g.getProperties().stream())
                .anyMatch(p -> p.getName().equals("GRA")), "GRA property should be present in group");
    }

    @Test
    @DisplayName("create and retrieve link definition")
    void testCreateLinkDefinition() {
        ItemDefinition productDefinition = new ItemDefinition();
        productDefinition.setName("Product");
        schemaManager.createItemDefinition(productDefinition);

        ItemDefinition coverDefinition = new ItemDefinition();
        coverDefinition.setName("Cover");
        schemaManager.createItemDefinition(coverDefinition);

        LinkDefinition linkDefinition = new LinkDefinition();
        linkDefinition.setInstanceMaxCardinality(1);
        linkDefinition.setSourceItemType("Product");
        linkDefinition.setTargetItemType("Cover");
        linkDefinition.setSourceCardinality(new Cardinality(1, 1));
        linkDefinition.setTargetCardinality(new Cardinality(0, null));
        linkDefinition.setSourceVersionAction(LinkDefinition.VersionAction.COPY);
        linkDefinition.setTargetVersionAction(LinkDefinition.VersionAction.MOVE);

        PropertyDefinition propDef1 = new PropertyDefinition();
        propDef1.setName("prop1");
        propDef1.setType(PropertyType.STRING);

        PropertyGroupDefinition groupDef = new PropertyGroupDefinition();
        groupDef.setName("testGroup");

        PropertyDefinition propDef2 = new PropertyDefinition();
        propDef2.setName("prop2");
        propDef2.setType(PropertyType.INT);

        groupDef.setProperties(Set.of(propDef2));
        linkDefinition.setPropertyGroups(Set.of(groupDef));
        linkDefinition.setProperties(Set.of(propDef1));
        schemaManager.createLinkDefinition(linkDefinition);

        Set<LinkDefinition> definitions = schemaManager.retrieveLinkDefinitions();
        assertEquals(1, definitions.size(), "Incorrect number of relationship definitions");

        Optional<LinkDefinition> schemaOpt = schemaManager.retrieveLinkDefinition(linkDefinition.getUid());
        assertTrue(schemaOpt.isPresent(), "Schema not returned");
        assertEquals(linkDefinition, schemaOpt.get());
    }

    @Test
    @DisplayName("add property to an item definition")
    void testAddPropertyToItemDefinition() {
        ItemDefinition itemDef = new ItemDefinition();
        itemDef.setName("Product");
        itemDef = schemaManager.createItemDefinition(itemDef);

        PropertyDefinition propDef = new PropertyDefinition();
        propDef.setName("Title");
        propDef.setDescription("A title");
        propDef.setType(PropertyType.STRING);

        PropertyDefinition created = schemaManager.createItemPropertyDefinition(itemDef.getUid(), propDef);
        assertNotNull(created);

        var itemDef2 = schemaManager.retrieveItemDefinition(itemDef.getName());
        assertTrue(itemDef2.isPresent());
        var updatedProp = itemDef2.get().getProperties().stream().filter(p -> p.getName().equals("Title")).findFirst();
        assertTrue(updatedProp.isPresent());
    }

    @Test
    @DisplayName("fail to update nonexistent property")
    void testUpdateNonexistentProperty() {
        assertThrows(SchemaException.class, () -> schemaManager.updatePropertyDefinition("badId", "newISBN", "New ISBN description"));
    }

    @Test
    @DisplayName("update a property definition")
    void testUpdatePropertyDefinition() {
        ItemDefinition itemDefinition = new ItemDefinition();
        itemDefinition.setName("Product");

        PropertyDefinition isbnPropertyDefinition = new PropertyDefinition();
        isbnPropertyDefinition.setName("ISBN");
        isbnPropertyDefinition.setDescription("An ISBN");
        isbnPropertyDefinition.setType(PropertyType.STRING);
        itemDefinition.setProperties(Set.of(isbnPropertyDefinition));

        var def = schemaManager.createItemDefinition(itemDefinition);
        var propOpt = def.getProperties().stream().filter(p -> p.getName().equals("ISBN")).findFirst();
        assertTrue(propOpt.isPresent());
        var isbnProp = propOpt.get();

        PropertyDefinition newDef = schemaManager.updatePropertyDefinition(isbnProp.uid, "newISBN", "New ISBN description");
        assertEquals("newISBN", newDef.getName());
        assertEquals("New ISBN description", newDef.getDescription());

        var photoDef2 = schemaManager.retrieveItemDefinition(def.getName());
        assertTrue(photoDef2.isPresent());
        var updatedProp = photoDef2.get().getProperties().stream().filter(p -> p.getName().equals("newISBN")).findFirst();
        assertTrue(updatedProp.isPresent());
    }

}

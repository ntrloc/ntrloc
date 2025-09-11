package org.ntrloc.graph.db.schema;

import com.hazelcast.map.IMap;
import org.apache.commons.io.FileUtils;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.janusgraph.core.PropertyKey;
import org.janusgraph.core.VertexLabel;
import org.janusgraph.core.schema.JanusGraphIndex;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.schema.impl.SchemaManagerImpl;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class SchemaManagerTest {

    private JanusGraph janusGraph;
    private GraphTraversalSource traversalSource;
    private SchemaManager schemaManager;

    @BeforeEach
    void setup() throws IOException {
        String indexPath = "target/db/lucene";
        File indexDir = new File(indexPath);
        if (indexDir.exists()) {
            FileUtils.deleteDirectory(indexDir);
        }

        janusGraph = JanusGraphFactory.build()
                .set("storage.backend", "inmemory")
                .set("index.search.backend", "lucene")
                .set("index.search.directory", indexPath)
                .open();
        traversalSource = janusGraph.traversal();
        ClusterService clusterService = mock(ClusterService.class);
        doReturn(mock(IMap.class)).when(clusterService).getMap(anyString());
        schemaManager = new SchemaManagerImpl(janusGraph, traversalSource, clusterService);
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

        JanusGraphManagement management = janusGraph.openManagement();
        try {
            VertexLabel label = management.getVertexLabel("Product");
            assertNotNull(label, "vertex label should not be null");

            PropertyKey isbnKey = management.getPropertyKey("Product_ISBN");
            assertNotNull(isbnKey, "ISBN key should not be null");
            PropertyKey graKey = management.getPropertyKey("Product_GRA");
            assertNotNull(graKey, "GRA key should not be null");

            JanusGraphIndex index = management.getGraphIndex("Product");
            assertNotNull(index, "index should not be null");

            PropertyKey[] indexKeys = index.getFieldKeys();
            assertEquals(2, indexKeys.length, "Incorrect number of index keys");

        } finally {
            management.rollback();
        }
    }

    @Test
    @DisplayName("create and retrieve relationship definition")
    void testCreateLinkDefinition() {
        LinkDefinition linkDefinition = new LinkDefinition();
        linkDefinition.setInstanceMaxCardinality(1);
        linkDefinition.setSourceEntity("Product");
        linkDefinition.setTargetEntity("Cover");
        linkDefinition.setSourceCardinality(new Cardinality(1, 1));
        linkDefinition.setTargetCardinality(new Cardinality(0, null));
        linkDefinition.setSourceVersionAction(LinkDefinition.VersionAction.COPY);
        linkDefinition.setTargetVersionAction(LinkDefinition.VersionAction.MOVE);
        linkDefinition.setName("has-cover");

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

        Optional<LinkDefinition> schemaOpt = schemaManager.retrieveLinkDefinition(linkDefinition.getName());
        assertTrue(schemaOpt.isPresent(), "Schema not returned");
        assertEquals(linkDefinition, schemaOpt.get());
    }

}

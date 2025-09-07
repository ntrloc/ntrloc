package org.ntrloc.graph.db;

import com.hazelcast.map.IMap;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.impl.ItemManagerImpl;
import org.ntrloc.graph.db.schema.Cardinality;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.db.schema.impl.SchemaManagerImpl;
import org.ntrloc.graph.db.storage.BinaryStorageAdapter;
import org.ntrloc.graph.db.traversal.mutator.Mutator;
import org.ntrloc.graph.db.traversal.projector.Projector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@DisplayName("An entity manager")
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
        Mutator mutator = mock(Mutator.class);
        Projector projector = mock(Projector.class);
        doReturn(mock(IMap.class)).when(clusterService).getMap(anyString());
        schemaManager = new SchemaManagerImpl(janusGraph, traversalSource, clusterService);
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

        schemaManager.createEntityDefinition(photoDefinition);

        ItemDefinition photographerDefinition = new ItemDefinition();
        photographerDefinition.setName("Photographer");
        PropertyDefinition photographerNameDefinition = new PropertyDefinition("name", PropertyType.STRING, "");
        photographerDefinition.setProperties(Set.of(photographerNameDefinition));
        schemaManager.createEntityDefinition(photographerDefinition);

        LinkDefinition linkDefinition = new LinkDefinition();
        linkDefinition.setName("created");
        linkDefinition.setSourceEntity(photographerDefinition.getName());
        linkDefinition.setTargetEntity(photoDefinition.getName());
        linkDefinition.setSourceCardinality(new Cardinality(1, 1));
        linkDefinition.setSourceVersionAction(LinkDefinition.VersionAction.COPY);
        linkDefinition.setTargetCardinality(new Cardinality(0, null));
        linkDefinition.setTargetVersionAction(LinkDefinition.VersionAction.MOVE);
        schemaManager.createRelationshipDefinition(linkDefinition);
    }

    @Test
    void testCreateEntity() {

    }

}

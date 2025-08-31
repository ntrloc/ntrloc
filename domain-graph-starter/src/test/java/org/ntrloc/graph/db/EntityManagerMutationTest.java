package org.ntrloc.graph.db;

import com.hazelcast.map.IMap;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.impl.EntityManagerImpl;
import org.ntrloc.graph.db.schema.Cardinality;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.db.schema.impl.SchemaManagerImpl;
import org.ntrloc.graph.db.storage.BinaryStorageAdapter;
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
class EntityManagerMutationTest {

    private static final Logger LOG = LoggerFactory.getLogger(EntityManagerMutationTest.class);

    private GraphTraversalSource traversalSource;
    private JanusGraph janusGraph;

    private SchemaManager schemaManager;
    private EntityManager entityManager;

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
        doReturn(mock(IMap.class)).when(clusterService).getMap(anyString());
        schemaManager = new SchemaManagerImpl(janusGraph, traversalSource, clusterService);
        entityManager = new EntityManagerImpl(traversalSource, adapter, schemaManager);
        setUpSchema();
    }

    private void setUpSchema() {
        EntityDefinition photoDefinition = new EntityDefinition();
        photoDefinition.setName("Photo");

        PropertyDefinition nameDefinition = new PropertyDefinition("name", PropertyType.STRING, "");
        PropertyDefinition numberDefinition = new PropertyDefinition("number", PropertyType.INT, "");
        PropertyDefinition booleanDefinition = new PropertyDefinition("boolean", PropertyType.BOOLEAN, "");
        PropertyDefinition dateDefinition = new PropertyDefinition("someDate", PropertyType.DATE, "");
        PropertyDefinition doublePropertyDefinition = new PropertyDefinition("double", PropertyType.DOUBLE, "");
        PropertyDefinition intListDefinition = new PropertyDefinition("intList", PropertyType.INT_LIST, "");
        photoDefinition.setProperties(Set.of(nameDefinition, numberDefinition, booleanDefinition, dateDefinition, doublePropertyDefinition, intListDefinition));

        schemaManager.createEntityDefinition(photoDefinition);

        EntityDefinition photographerDefinition = new EntityDefinition();
        photographerDefinition.setName("Photographer");
        PropertyDefinition photographerNameDefinition = new PropertyDefinition("name", PropertyType.STRING, "");
        photographerDefinition.setProperties(Set.of(photographerNameDefinition));
        schemaManager.createEntityDefinition(photographerDefinition);

        RelationshipDefinition relationshipDefinition = new RelationshipDefinition();
        relationshipDefinition.setName("created");
        relationshipDefinition.setSourceEntity(photographerDefinition.getName());
        relationshipDefinition.setTargetEntity(photoDefinition.getName());
        relationshipDefinition.setSourceCardinality(new Cardinality(1, 1));
        relationshipDefinition.setSourceVersionAction(RelationshipDefinition.VersionAction.COPY);
        relationshipDefinition.setTargetCardinality(new Cardinality(0, null));
        relationshipDefinition.setTargetVersionAction(RelationshipDefinition.VersionAction.MOVE);
        schemaManager.createRelationshipDefinition(relationshipDefinition);
    }

    @Test
    void testCreateEntity() {

    }

}

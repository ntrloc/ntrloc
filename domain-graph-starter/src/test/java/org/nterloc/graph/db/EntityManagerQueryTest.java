package org.nterloc.graph.db;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nterloc.graph.db.impl.EntityManagerImpl;
import org.nterloc.graph.db.query.Query;
import org.nterloc.graph.db.query.QueryResult;
import org.nterloc.graph.db.query.QueryReturn;
import org.nterloc.graph.db.query.QuerySelection;
import org.nterloc.graph.db.schema.SchemaManager;
import org.nterloc.graph.db.storage.BinaryStorageAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

@DisplayName("An entity manager")
public class EntityManagerQueryTest {

    private static final Logger LOG = LoggerFactory.getLogger(EntityManagerQueryTest.class);

    private GraphTraversalSource traversalSource;
    private JanusGraph janusGraph;
    private EntityManager manager;

    @BeforeEach
    public void init() throws IOException {
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
        SchemaManager schemaManager = mock(SchemaManager.class);
        manager = new EntityManagerImpl(traversalSource, adapter, schemaManager);
    }

    @Disabled
    @Test
    @DisplayName("should be able to return all entities of a given type")
    void testRetrieveEntities() {
        QuerySelection querySelection = new QuerySelection();
        QueryReturn queryReturn = new QueryReturn();
        Query query = new Query(querySelection, queryReturn);
        QueryResult result = manager.executeQuery(query);
        assertNotNull(result, "null result");
    }


}

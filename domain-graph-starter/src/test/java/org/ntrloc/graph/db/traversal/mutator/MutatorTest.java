package org.ntrloc.graph.db.traversal.mutator;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.EntityStatus;
import org.ntrloc.graph.db.LabelConstants;
import org.ntrloc.graph.db.mutation.StringProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.ntrloc.graph.db.PropertyConstants.STATUS_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.TRANSACTION_ID_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.VERSION_PROPERTY;

class MutatorTest {

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
    }

    class NodeProjection {
        public String id;
        public String label;
        public Map<String, Object> properties;
    }

    class NodeRevisionProjection {
        public String id;
        public String label;
        public Map<String, Object> properties;
        public Map<String, Object> revision;
        public Map<String, Object> version;
    }

    private Map<String, Object> flattenProperties(Map<String, Object> props) {
        Map<String, Object> flattened = new HashMap<>();
        for (var entry : props.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof List list) {
                if (list.size() == 1) {
                    flattened.put(key, list.get(0));
                } else {
                    flattened.put(key, list);
                }
            } else {
                flattened.put(key, value);
            }
        }
        return flattened;
    }

    @Test
    void testCreateNode() {
        Mutator mutator = new Mutator(traversalSource);

        var label = "Photo";
        var props = Set.of(new StringProperty("name", "photo1.jpg"));
        mutator.createNode(label, props);

        mutator.checkpoint();

        // we've created the node, but we haven't committed the mutation, so the new node should be uncommitted and have the correct transaction ID
        Map<Object, Object> valueMap = traversalSource.V().hasLabel("Photo").valueMap().next();
        List<String> status = (List<String>) valueMap.get(STATUS_PROPERTY);
        assertEquals(1, status.size());
        assertEquals(EntityStatus.UNCOMMITTED.toString(), status.get(0));

        List<String> txId = (List<String>) valueMap.get(TRANSACTION_ID_PROPERTY);
        assertEquals(1, txId.size());
        assertEquals(mutator.getTransaction().getId(), txId.get(0));

        // commit the mutation and check that the node is in a normal state now
        mutator.commit();
        valueMap = traversalSource.V().hasLabel("Photo").valueMap().next();

        status = (List<String>) valueMap.get(STATUS_PROPERTY);
        assertEquals(1, status.size());
        assertEquals(EntityStatus.NORMAL.toString(), status.get(0));

        assertNull(valueMap.get(TRANSACTION_ID_PROPERTY));
    }

    @Test
    void testUpdateNodeProperties() {
        Mutator mutator = new Mutator(traversalSource);
        var label = "Photo";
        var props = Set.of(
                new StringProperty("name", "photo1.jpg"),
                new StringProperty("colorspace", "RGB")
        );
        var newId = mutator.createNode(label, props);
        mutator.commit();

        // add, remove, and modify properties
        var updatedProps = Set.of(
                new StringProperty("name", "photo2.jpg"),
                new StringProperty("colorspace", null),
                new StringProperty("author", "Bill")
        );
        mutator.updateNode(newId, updatedProps);

        mutator.checkpoint();

        // check that the revision was created
        var checkpointResult = traversalSource.V().hasLabel("Photo")
                .project("props", "revision", "version")
                .by(__.valueMap())
                .by(__.in(LabelConstants.IS_REVISION_OF_LABEL).project("props").by(__.valueMap()))
                .by(__.in(LabelConstants.HAS_PREVIOUS_VERSION_LABEL))
                .next();
        assertNotNull(checkpointResult.get("revision"), "revision should not be null after checkpoint");
        assertNull(checkpointResult.get("version"), "version should be null after checkpoint");

        // check that the new version is prepared
        mutator.prepare();

        mutator.checkpoint();

        var prepareResult = traversalSource.V().hasLabel("Photo").has(STATUS_PROPERTY, EntityStatus.NORMAL.toString())
                .project("props", "revision", "newVersion")
                        .by(__.valueMap())
                        .by(__.in(LabelConstants.IS_REVISION_OF_LABEL))
                        .by(__.in(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).project("props").by(__.valueMap())).next();

        assertNull(prepareResult.get("revision"), "revision should be null after prepare");
        var newVersion = (Map)prepareResult.get("newVersion");
        assertNotNull(newVersion, "new node version not found");
        var newProps = flattenProperties((Map)newVersion.get("props"));
        assertEquals("photo2.jpg", newProps.get("Photo_name"));
        assertNull(newProps.get("Photo_colorspace"));
        assertEquals("Bill", newProps.get("Photo_author"));


        // check that the node has a new committed version after commit
        mutator.commit();

        var commitResult = traversalSource.V().hasLabel("Photo").has(STATUS_PROPERTY, EntityStatus.NORMAL.toString()).has(VERSION_PROPERTY, 2)
                .project("props", "previousVersion")
                .by(__.valueMap())
                .by(__.out(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).project("props").by(__.valueMap())).next();
        assertNotNull(commitResult.get("previousVersion"), "previous version should not be null after commit");

        var commitProps = flattenProperties((Map)commitResult.get("props"));
        assertEquals(2, commitProps.get("version"));
        assertEquals("photo2.jpg", commitProps.get("Photo_name"));
        assertNull(commitProps.get("Photo_colorspace"));
        assertEquals("Bill", commitProps.get("Photo_author"));
    }

}

package org.ntrloc.graph.db.traversal.mutator;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.codehaus.jackson.map.ObjectMapper;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.EntityStatus;
import org.ntrloc.graph.db.LabelConstants;
import org.ntrloc.graph.db.language.StringProperty;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ntrloc.graph.db.PropertyConstants.STATUS_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.TRANSACTION_ID_PROPERTY;
import static org.ntrloc.graph.db.PropertyConstants.VERSION_PROPERTY;

class NodeMutationResult extends Node {
    public Node revision;
    public Node version;
}

class MutatorTest {

    private JanusGraph janusGraph;
    private GraphTraversalSource traversalSource;
    private ObjectMapper objectMapper = new ObjectMapper();

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

    private void assertProperties(Map<String, Object> props, Object... keysAndValues) {
        List<Object> expectedValues = Arrays.stream(keysAndValues).toList();
        assertTrue(expectedValues.size() % 2 == 0, "expected even number of keys and values");
        for (int i = 0; i < expectedValues.size(); i += 2) {
            Object key = expectedValues.get(i);
            if (!(key instanceof String)) {
                throw new IllegalArgumentException("Even-numbered keys must be strings");
            }
        }

        for (int i = 0; i < expectedValues.size(); i += 2) {
            String key = (String) expectedValues.get(i);
            Object expectedValue = expectedValues.get(i + 1);
            if (expectedValue == null) {
                assertFalse(props.containsKey(key), "found expected missing key " + key);
            } else {
                assertTrue(props.containsKey(key), "key " + key + " not found");
                assertEquals(expectedValue, props.get(key), "value mismatch for key " + key);
            }
        }
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

        Function<GraphTraversal<Vertex, Vertex>, GraphTraversal<?, NodeMutationResult>> nodeWithUpdates = (t) -> {
            var mapTraversal = t.project("id", "label", "properties", "revision", "version")
                    .by(__.id())
                    .by(__.label())
                    .by(__.valueMap())
                    .by(__.in(LabelConstants.IS_REVISION_OF_LABEL).project("id", "label", "properties").by(__.id()).by(__.label()).by(__.valueMap()))
                    .by(__.in(LabelConstants.HAS_PREVIOUS_VERSION_LABEL).project("id", "label", "properties").by(__.id()).by(__.label()).by(__.valueMap()));
            return mapTraversal.map(traversal -> {
                var obj = traversal.get();
                return objectMapper.convertValue(obj, NodeMutationResult.class);
            });
        };

        // check that the revision was created
        var checkpointResult = nodeWithUpdates.apply(traversalSource.V().hasLabel("Photo")).next();
        var checkpointNode = objectMapper.convertValue(checkpointResult, NodeMutationResult.class);

        assertNotNull(checkpointNode.revision, "revision should not be null after checkpoint");
        assertNull(checkpointNode.version, "previous version should be null after checkpoint");

        // check that the new version is prepared
        mutator.prepare();
        mutator.checkpoint();

        var prepareNode = nodeWithUpdates.apply(traversalSource.V().hasLabel("Photo").has(STATUS_PROPERTY, EntityStatus.NORMAL.toString())).next();

        assertNull(prepareNode.revision, "revision should be null after prepare");
        var newVersion = prepareNode.version;
        assertNotNull(newVersion, "new node version not found");
        var newProps = newVersion.properties;
        assertProperties(newProps,"Photo_name", "photo2.jpg", "Photo_author", "Bill", "Photo_colorspace", null);

        // check that the node has a new committed version after commit
        mutator.commit();

        var commitNode = nodeWithUpdates.apply(traversalSource.V().hasLabel("Photo").has(STATUS_PROPERTY, EntityStatus.NORMAL.toString()).has(VERSION_PROPERTY, 1)).next();

        assertNotNull(commitNode.version, "previous version should not be null after commit");
        var newNode = commitNode.version;
        var commitProps = newNode.properties;
        assertProperties(commitProps, "Photo_name", "photo2.jpg", "Photo_author", "Bill", "Photo_colorspace", null, "version", 2);
    }

}

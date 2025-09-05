package org.ntrloc.graph.db.projector;

import org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.__;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.ntrloc.graph.db.PropertyConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Projector {

    private static final Logger LOG = LoggerFactory.getLogger(Projector.class);

    private GraphTraversalSource traversalSource;

    public Projector(GraphTraversalSource traversalSource) {
        this.traversalSource = traversalSource;
    }

    Iterable<NodeProjection> project(NodeProjectionSpec spec) {
        GraphTraversal<?, ?> traversal = traversalSource.V();
        traversal = select(traversal, spec.getNodeSelector());
        var mapTraversal = project(traversal, spec);

        GraphTraversal<?, NodeProjection> projectionTraversal = mapTraversal.map(input -> {
            Map<String, Object> value = input.get();
            String uid = (String) value.get(PropertyConstants.UNIQUE_ID_PROPERTY);
            String nodeType = (String) value.get(PropertyConstants.NODE_TYPE_PROPERTY);
            Map<String, Object> properties = (Map<String, Object>) value.get("properties");
            NodeProjection projection = new NodeProjection();
            projection.setId(uid);
            projection.setNodeType(nodeType);
            projection.setProperties(properties);
            return projection;
        });

        List<NodeProjection> nodeProjections = new ArrayList<>();
        while (projectionTraversal.hasNext()) {
            var next =  projectionTraversal.next();
            LOG.info("Got next {}", next);
            nodeProjections.add(next);
        }
        return nodeProjections;
    }

    /** Returns a new traverser that adds a node selection to the given traversal. */
    private GraphTraversal<?, ?> select(GraphTraversal<?, ?> traversal, NodeSelector selector) {
        GraphTraversal<?, ?> result;
        switch (selector) {
            case LabelSelector labelSelector -> result = traversal.hasLabel(labelSelector.getLabel());
            default -> throw new IllegalStateException("Unsupported selector: " + selector);
        }
        return result;
    }

    /** Returns a traverser that adds a property projection to the given traverser. */
    private GraphTraversal<?, Map<String, Object>> project(GraphTraversal<?, ?> traversal, NodeProjectionSpec spec) {
        // TODO: we need to capture the ID and type of the node, at least...
        List<String> properties = spec.getProperties();
        var retTraversal = traversal.project(PropertyConstants.UNIQUE_ID_PROPERTY, PropertyConstants.NODE_TYPE_PROPERTY, "properties")
                .by(__.values(PropertyConstants.UNIQUE_ID_PROPERTY))
                .by(__.values(PropertyConstants.NODE_TYPE_PROPERTY))
                .by(__.valueMap(properties.toArray(new String[0])));
        return retTraversal;
    }

}

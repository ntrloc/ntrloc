package org.ntrloc.graph.db.projector;

import org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.__;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.ntrloc.graph.db.PropertyConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class Projector {

    private static final Logger LOG = LoggerFactory.getLogger(Projector.class);

    private GraphTraversalSource traversalSource;

    public Projector(GraphTraversalSource traversalSource) {
        this.traversalSource = traversalSource;
    }

    Iterable<NodeProjection> project(NodeProjectionSpec spec) {
        GraphTraversal<?, ?> traversal = traversalSource.V();
        traversal = select(traversal, spec.getNodeSelector());
        var mapTraversal = projectNode(traversal, spec);

        GraphTraversal<?, NodeProjection> projectionTraversal = mapTraversal.map(input -> {
            Map<String, Object> value = input.get();
            String uid = (String) value.get(PropertyConstants.UNIQUE_ID_PROPERTY);
            String nodeType = (String) value.get(PropertyConstants.NODE_TYPE_PROPERTY);
            Map<String, Object> properties = (Map<String, Object>) value.get("properties");
            NodeProjection projection = new NodeProjection();
            projection.setId(uid);
            projection.setNodeType(nodeType);
            projection.setProperties(properties);

            // TODO: map in the traversed link projections

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
    private GraphTraversal<?, Map<String, Object>> projectNode(GraphTraversal<?, ?> traversal, NodeProjectionSpec spec) {
        List<String> properties = spec.getProperties();

        Map<String, GraphTraversal<?, ?>> projectionTraversals = new TreeMap<>();
        projectionTraversals.put(PropertyConstants.UNIQUE_ID_PROPERTY, __.values(PropertyConstants.UNIQUE_ID_PROPERTY));
        projectionTraversals.put(PropertyConstants.NODE_TYPE_PROPERTY, __.values(PropertyConstants.NODE_TYPE_PROPERTY));
        projectionTraversals.put("properties", __.valueMap(properties.toArray(new String[0])));

        if (spec.getLinks() != null) {
            for (Map.Entry<String, LinkProjectionSpec> entry : spec.getLinks().entrySet()) {
                String linkAlias = entry.getKey();
                LinkProjectionSpec linkSpec = entry.getValue();
                var linkTraversal = linkSpec.getDirection().equals(Direction.IN) ? __.inE(linkSpec.getLinkName() + "-out").outV() : __.outE(linkSpec.getLinkName() + "-in").inV();
                projectionTraversals.put(linkAlias, projectLink(linkTraversal, linkSpec));
            }
        }

        List<String> projectionKeys = projectionTraversals.keySet().stream().toList();
        var projectionTraversal = traversal.project(projectionKeys.get(0), projectionKeys.subList(1, projectionKeys.size()).toArray(new String[0]));
        for (var trav: projectionTraversals.values()) {
            projectionTraversal = projectionTraversal.by(trav);
        }
        return projectionTraversal;
    }

    private GraphTraversal<?, Map<String, Object>> projectLink(GraphTraversal<?, ?> traversal, LinkProjectionSpec spec) {
        List<String> properties = spec.getProperties();

        Map<String, GraphTraversal<?, ?>> projectionTraversals = new TreeMap<>();
        projectionTraversals.put(PropertyConstants.UNIQUE_ID_PROPERTY, __.values(PropertyConstants.UNIQUE_ID_PROPERTY));
        projectionTraversals.put(PropertyConstants.NODE_TYPE_PROPERTY, __.values(PropertyConstants.NODE_TYPE_PROPERTY));
        projectionTraversals.put("properties", __.valueMap(properties.toArray(new String[0])));

        // TODO: add projection for the link target/source
        // remamber that this traversal we're given is going to be sitting on a relationship node,
        // so to get to the "target" or "source" we need to follow the <LINKNAME>-in or <LINKNAME>-out edge
        if (spec.getDirection().equals(Direction.IN)) {
            var sourceTraversal = __.inE(spec.getLinkName() + "-in").outV();
            projectionTraversals.put("source", projectNode(sourceTraversal, spec.getTargetProjection()));
        } else {
            var targetTraversal = __.outE(spec.getLinkName() + "-out").inV();
            projectionTraversals.put("target", projectNode(targetTraversal, spec.getTargetProjection()));
        }

        List<String> projectionKeys = projectionTraversals.keySet().stream().toList();
        var projectionTraversal = traversal.project(projectionKeys.get(0), projectionKeys.subList(1, projectionKeys.size()).toArray(new String[0]));
        for (var trav: projectionTraversals.values()) {
            projectionTraversal = projectionTraversal.by(trav);
        }
        return projectionTraversal;
    }

}

package org.ntrloc.graph.db.projector;

import org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.__;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.ntrloc.graph.db.PropertyConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
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
        var projectionTraversal = projectNodes(traversal, spec);

        List<NodeProjection> nodeProjections = new ArrayList<>();
        while (projectionTraversal.hasNext()) {
            var next =  projectionTraversal.next();
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
    private GraphTraversal<?, NodeProjection> projectNodes(GraphTraversal<?, ?> traversal, NodeProjectionSpec spec) {
        Map<String, GraphTraversal<?, ?>> projectionTraversals = new TreeMap<>();
        projectionTraversals.put(PropertyConstants.UNIQUE_ID_PROPERTY, __.values(PropertyConstants.UNIQUE_ID_PROPERTY));
        projectionTraversals.put(PropertyConstants.NODE_TYPE_PROPERTY, __.values(PropertyConstants.NODE_TYPE_PROPERTY));
        if (spec.getProperties() != null) {
            projectionTraversals.put("properties", __.valueMap(spec.getProperties().toArray(new String[0])));
        }

        if (spec.getLinks() != null) {
            for (Map.Entry<String, LinkProjectionSpec> entry : spec.getLinks().entrySet()) {
                String linkAlias = entry.getKey();
                LinkProjectionSpec linkSpec = entry.getValue();
                String otherNodeName = linkSpec.getNodeLabel();
                var linkTraversal = linkSpec.getDirection().equals(Direction.IN) ?
                        __.in(getLinkPropertyOutEdgeName(linkSpec.getLinkName()))
                                .where(__.in(getLinkPropertyInEdgeName(linkSpec.getLinkName())).has(PropertyConstants.NODE_TYPE_PROPERTY, otherNodeName))
                        :
                        __.out(getLinkPropertyInEdgeName(linkSpec.getLinkName()))
                                .where(__.out(getLinkPropertyOutEdgeName(linkSpec.getLinkName())).has(PropertyConstants.NODE_TYPE_PROPERTY, otherNodeName));
                projectionTraversals.put(linkAlias, projectLinks(linkTraversal, linkSpec));
            }
        }

        List<String> projectionKeys = projectionTraversals.keySet().stream().toList();
        var projectionTraversal = traversal.project(projectionKeys.get(0), projectionKeys.subList(1, projectionKeys.size()).toArray(new String[0]));
        for (var trav: projectionTraversals.values()) {
            projectionTraversal = projectionTraversal.by(trav);
        }

        return projectionTraversal.map(input -> {
            Map<String, Object> value = input.get();
            String uid = (String) value.get(PropertyConstants.UNIQUE_ID_PROPERTY);
            String nodeType = (String) value.get(PropertyConstants.NODE_TYPE_PROPERTY);
            Map<String, Object> nodeProps = (Map<String, Object>) value.get("properties");
            NodeProjection projection = new NodeProjection();
            projection.setId(uid);
            projection.setNodeType(nodeType);
            projection.setProperties(nodeProps);

            if (spec.getLinks() != null) {
                Map<String, List<LinkProjection>> links = new HashMap<>();
                for (var entry: spec.getLinks().keySet()) {
                    links.put(entry, (List<LinkProjection>) value.get(entry));
                }
                projection.setLinks(links);
            }

            return projection;
        });
    }

    private GraphTraversal<?, List<LinkProjection>> projectLinks(GraphTraversal<?, ?> traversal, LinkProjectionSpec spec) {
        Map<String, GraphTraversal<?, ?>> projectionTraversals = new TreeMap<>();
        projectionTraversals.put(PropertyConstants.UNIQUE_ID_PROPERTY, __.values(PropertyConstants.UNIQUE_ID_PROPERTY));
        projectionTraversals.put(PropertyConstants.NODE_TYPE_PROPERTY, __.values(PropertyConstants.NODE_TYPE_PROPERTY));
        if (spec.getProperties() != null) {
            projectionTraversals.put("properties", __.valueMap(spec.getProperties().toArray(new String[0])));
        }

        NodeProjectionSpec nodeProjectionSpec = spec.getNodeProjection();
        if (nodeProjectionSpec == null) {
            nodeProjectionSpec = new NodeProjectionSpec();
        }

        // add the node on the other side of the link to the projection
        if (spec.getDirection().equals(Direction.IN)) {
            var sourceTraversal = __.inE( getLinkPropertyInEdgeName(spec.getLinkName())).outV();
            var projectedNodes = projectNodes(sourceTraversal, nodeProjectionSpec);
            projectionTraversals.put("source", projectedNodes);
        } else {
            var targetTraversal = __.outE( getLinkPropertyOutEdgeName(spec.getLinkName())).inV();
            var projectedNodes = projectNodes(targetTraversal, nodeProjectionSpec);
            projectionTraversals.put("target", projectedNodes);
        }

        List<String> projectionKeys = projectionTraversals.keySet().stream().toList();
        var projectionTraversal = traversal.project(projectionKeys.get(0), projectionKeys.subList(1, projectionKeys.size()).toArray(new String[0]));
        for (var trav: projectionTraversals.values()) {
            projectionTraversal = projectionTraversal.by(trav);
        }
        var foldTraversal = projectionTraversal.fold();
        return foldTraversal.map(inputs -> {
            List<Map<String, Object>> inputList = inputs.get();
            return inputList.stream().map(value -> {
                LinkProjection linkProjection = null;
                if (value.containsKey("source")) {
                    IncomingLinkProjection in = new IncomingLinkProjection();
                    in.setSource((NodeProjection) value.get("source"));
                    linkProjection = in;
                } else if (value.containsKey("target")) {
                    OutgoingLinkProjection out = new OutgoingLinkProjection();
                    out.setTarget((NodeProjection) value.get("target"));
                    linkProjection = out;
                } else {
                    LOG.warn("mapped link contains no source or target node");
                }
                linkProjection.setProperties((Map<String, Object>) value.get("properties"));

                return linkProjection;
            }).toList();
        });
    }

    private String getLinkPropertyInEdgeName(String linkName) {
        return linkName + "-in";
    }

    private String getLinkPropertyOutEdgeName(String linkName) {
        return linkName + "-out";
    }

}

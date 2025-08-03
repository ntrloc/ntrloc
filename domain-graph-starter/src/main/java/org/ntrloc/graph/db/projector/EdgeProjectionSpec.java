package org.ntrloc.graph.db.projector;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EdgeProjectionSpec extends ProjectionSpec {

    private static final Logger LOG = LoggerFactory.getLogger(EdgeProjectionSpec.class);

    private String edgeLabel;

    private String vertexLabel;

    private Direction direction;

    private boolean required;

    private VertexProjectionSpec vertexProjectionSpec;

    public EdgeProjectionSpec(String edgeLabel, Direction direction, String vertexLabel, VertexProjectionSpec vertexProjectionSpec) {
        this(edgeLabel, direction, vertexLabel, false, vertexProjectionSpec);
    }

    public EdgeProjectionSpec(String edgeLabel, Direction direction, String vertexLabel, boolean required, VertexProjectionSpec vertexProjectionSpec) {
        this.edgeLabel = edgeLabel;
        this.direction = direction;
        this.vertexLabel = vertexLabel;
        this.required = required;
        this.vertexProjectionSpec = vertexProjectionSpec;
    }

    public boolean isRequired() {
        return required;
    }

    public String getEdgeLabel() {
        return edgeLabel;
    }

    public String getVertexLabel() {
        return vertexLabel;
    }

    public Direction getDirection() {
        return direction;
    }

    public GraphTraversal<?, List<EdgeProjection>> construct() {

        var baseTraversal = switch(direction) {
            case Direction.IN -> __.inE(edgeLabel).where(__.outV().hasLabel(vertexLabel));
            case Direction.OUT -> __.outE(edgeLabel).where(__.inV().hasLabel(vertexLabel));
            default -> throw new IllegalArgumentException("Invalid direction: " + direction);
        };

        var projectionMap = new HashMap<String, GraphTraversal<?, ?>>();
        projectionMap.put("label", __.label());
        projectionMap.put("id", __.id());
        projectionMap.put("properties", __.valueMap());
        projectionMap.put("target", vertexProjectionSpec.traversal(direction.equals(Direction.IN) ? __.outV() : __.inV()).construct());

        List<String> names = new ArrayList<>();
        List<GraphTraversal<?, ?>> values = new ArrayList<>();
        for (var entry : projectionMap.entrySet()) {
            names.add(entry.getKey());
            values.add(entry.getValue());
        }

        LOG.info("Using edge projection with keys {}", names);

        var retTraversal = baseTraversal
                .project(names.get(0), names.subList(1, names.size()).toArray(new String[0]));
        for (var traversal : values) {
            retTraversal = retTraversal.by(traversal);
        }

        return retTraversal.map(mapTraverser -> {
            var map = mapTraverser.get();
            org.ntrloc.graph.db.projector.EdgeProjection ret = new org.ntrloc.graph.db.projector.EdgeProjection(
                    (String) map.get("label"),
                    direction,
                    map.get("id"),
                    getSimplifiedProperties((Map) map.get("properties")),
                    (VertexProjection) map.get("target")
            );

            return ret;
        }).fold();

    }

}

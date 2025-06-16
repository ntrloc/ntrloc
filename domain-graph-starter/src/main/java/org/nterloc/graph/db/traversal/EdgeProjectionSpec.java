package org.nterloc.graph.db.traversal;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EdgeProjectionSpec {

    enum Direction {
        IN, OUT
    }

    private String label;
    private VertexProjectionSpec targetSpec;
    private Direction direction;

    public EdgeProjectionSpec(String label, Direction direction) {
        this.label = label;
        this.direction = direction;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getLabel() {
        return label;
    }

    public EdgeProjectionSpec target(VertexProjectionSpec targetSpec) {
        this.targetSpec = targetSpec;
        return this;
    }

    public GraphTraversal<?, List<EdgeProjection>> construct() {
        if (targetSpec == null) {
            switch (direction) {
                case IN:
                    targetSpec = new VertexProjectionSpec(__.outV());
                    break;
                case OUT:
                    targetSpec = new VertexProjectionSpec(__.inV());
                    break;
            }
        }

        // set up the names of the projections and the traversal used to populate each field
        var projectionMap = new HashMap<String, GraphTraversal<?, ?>>();
        projectionMap.put("label", __.label());
        projectionMap.put("id", __.id());
        projectionMap.put("properties", __.valueMap());
        projectionMap.put("target", targetSpec.construct());

        List<String> names = new ArrayList<>();
        List<GraphTraversal<?, ?>> values = new ArrayList<>();
        for (var entry : projectionMap.entrySet()) {
            names.add(entry.getKey());
            values.add(entry.getValue());
        }

        var retTraversal = direction == Direction.IN ? __.inE(label) : __.outE(label);
        var retProjectionTraversal = retTraversal.project(names.get(0), names.subList(1, names.size()).toArray(new String[0]));
        for (var traversal : values) {
            retTraversal = retTraversal.by(traversal);
        }

        return retProjectionTraversal.map(mapTraverser -> {
            var map = mapTraverser.get();
            return new EdgeProjection(
                    (String) map.get("label"),
                    map.get("id"),
                    (Map) map.get("properties"),
                    (VertexProjection) map.get("target")
            );
        }).fold();
    }

}

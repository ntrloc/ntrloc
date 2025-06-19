package org.ntrloc.graph.db.traversal;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VertexProjectionSpec {

    private GraphTraversal<?, Vertex> graphTraversal;

    List<String> properties;
    List<EdgeProjectionSpec> outboundEdges;
    List<EdgeProjectionSpec> inboundEdges;

    public VertexProjectionSpec(GraphTraversal<?, Vertex> graphTraversal) {
        this.graphTraversal = graphTraversal;
    }

    public VertexProjectionSpec properties(List<String> properties) {
        this.properties = properties;
        return this;
    }

    public VertexProjectionSpec properties(String... properties) {
        this.properties = List.of(properties);
        return this;
    }

    public VertexProjectionSpec edges(EdgeProjectionSpec... edges) {
        this.inboundEdges = Arrays.stream(edges).filter(edge -> edge.getDirection() == EdgeProjectionSpec.Direction.IN).toList();
        this.outboundEdges = Arrays.stream(edges).filter(edge -> edge.getDirection() == EdgeProjectionSpec.Direction.OUT).toList();
        return this;
    }

    public GraphTraversal<?, VertexProjection> construct() {
        var baseTraversal = (graphTraversal != null) ? graphTraversal : __.start();

        // set up the names of the projections and the traversal used to populate each field
        var projectionMap = new HashMap<String, GraphTraversal<?, ?>>();
        projectionMap.put("label", __.label());
        projectionMap.put("id", __.id());
        projectionMap.put("properties", properties == null ? __.valueMap() : __.valueMap(properties.toArray(new String[0])));

        if (inboundEdges != null) {
            for (EdgeProjectionSpec inSpec : inboundEdges) {
                projectionMap.put(String.format("in-%s", inSpec.getLabel()), inSpec.construct());
            }
        }

        if (outboundEdges != null) {
            for (EdgeProjectionSpec outSpec : outboundEdges) {
                projectionMap.put(String.format("out-%s", outSpec.getLabel()), outSpec.construct());
            }
        }

        List<String> names = new ArrayList<>();
        List<GraphTraversal<?, ?>> values = new ArrayList<>();
        for (var entry : projectionMap.entrySet()) {
            names.add(entry.getKey());
            values.add(entry.getValue());
        }

        var retTraversal = baseTraversal
                .project(names.get(0), names.subList(1, names.size()).toArray(new String[0]));
        for (var traversal : values) {
            retTraversal = retTraversal.by(traversal);
        }

        return retTraversal.map(mapTraverser -> {
            var map = mapTraverser.get();
            VertexProjection ret = new VertexProjection(
                    (String) map.get("label"),
                    map.get("id"),
                    (Map) map.get("properties")
            );

            var outKeys = map.keySet().stream().filter(key -> key.startsWith("out-")).toList();
            var outMap = new HashMap<String, List<EdgeProjection>>();
            for (var key: outKeys) {
                var relName = key.substring("out-".length());
                var value = (List<EdgeProjection>) map.get(key);
                outMap.put(relName, value);
            }
            ret.setOutboundEdges(outMap);

            var inKeys = map.keySet().stream().filter(key -> key.startsWith("in-")).toList();
            var inMap = new HashMap<String, List<EdgeProjection>>();
            for (var key: inKeys) {
                var relName = key.substring("in-".length());
                var value = (List<EdgeProjection>) map.get(key);
                inMap.put(relName, value);
            }
            ret.setInboundEdges(inMap);

            return ret;
        });
    }

}

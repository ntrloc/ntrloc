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

public class VertexProjectionSpec extends ProjectionSpec {

    private static final Logger LOG = LoggerFactory.getLogger(VertexProjectionSpec.class);

    private GraphTraversal<?, ?> graphTraversal;

    private Map<String, EdgeProjectionSpec> edges = new HashMap<>();

    public VertexProjectionSpec() {
        // creates a spec without any starting traversal. NOTE: you'll need to set the traversal
        // via the traversal() method before calling construct!
    }

    public VertexProjectionSpec traversal(GraphTraversal<?, ?> graphTraversal) {
        this.graphTraversal = graphTraversal;
        return this;
    }


    public VertexProjectionSpec properties(List<String> properties) {
        this.properties = properties;
        return this;
    }

    public VertexProjectionSpec properties(String... properties) {
        this.properties = List.of(properties);
        return this;
    }

    public VertexProjectionSpec edge(String projectionKey, EdgeProjectionSpec projectionSpec) {
        edges.put(projectionKey, projectionSpec);
        return this;
    }

    public GraphTraversal<?, org.ntrloc.graph.db.projector.VertexProjection> construct() {
        var baseTraversal = (graphTraversal != null) ? graphTraversal : __.start();

        // if the vertex spec includes required edges, add a filter for them
        if (edges != null && !edges.isEmpty()) {
            List<EdgeProjectionSpec> requiredSpecs = edges.values().stream().filter(EdgeProjectionSpec::isRequired).toList();
            for (EdgeProjectionSpec spec: requiredSpecs) {
                String edgeLabel = spec.getEdgeLabel();
                String vertexLabel = spec.getVertexLabel();
                Direction direction = spec.getDirection();
                baseTraversal = baseTraversal.where(direction.equals(Direction.IN) ? __.in(edgeLabel).hasLabel(vertexLabel) : __.out(edgeLabel).hasLabel(vertexLabel));
            }
        }

        // set up the names of the projections and the traversal used to populate each field
        var projectionMap = new HashMap<String, GraphTraversal<?, ?>>();
        projectionMap.put("label", __.label());
        projectionMap.put("id", __.id());
        projectionMap.put("properties", properties == null ? __.valueMap() : __.valueMap(properties.toArray(new String[0])));

        for (var entry : edges.entrySet()) {
            String edgeKey = entry.getKey();
            EdgeProjectionSpec spec = entry.getValue();
            projectionMap.put(edgeKey, spec.construct());
        }

        List<String> names = new ArrayList<>();
        List<GraphTraversal<?, ?>> values = new ArrayList<>();
        for (var entry : projectionMap.entrySet()) {
            names.add(entry.getKey());
            values.add(entry.getValue());
        }

        LOG.info("Using vertex projection with keys {}", names);

        var retTraversal = baseTraversal
                .project(names.get(0), names.subList(1, names.size()).toArray(new String[0]));
        for (var traversal : values) {
            retTraversal = retTraversal.by(traversal);
        }

        return retTraversal.map(mapTraverser -> {
            var map = mapTraverser.get();
            Map<String, Object> finalProperties = new HashMap<>();
            Map<String, Object> props = (Map) map.get("properties");
            if (props != null) {
                finalProperties.putAll(getSimplifiedProperties(props));
            }
            if (edges != null) {
                for (var entry : edges.entrySet()) {
                    String projectionKey = entry.getKey();
                    List<EdgeProjection> ep = (List) map.get(projectionKey);
                    finalProperties.put(projectionKey, ep);
                }
            }

            org.ntrloc.graph.db.projector.VertexProjection ret = new org.ntrloc.graph.db.projector.VertexProjection(
                    (String) map.get("label"),
                    map.get("id"),
                    finalProperties
            );

            return ret;
        });
    }

}

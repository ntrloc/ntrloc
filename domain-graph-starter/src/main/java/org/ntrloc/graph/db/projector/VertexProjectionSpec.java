package org.ntrloc.graph.db.projector;

import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.projector.filter.FilterSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VertexProjectionSpec {

    private static final Logger LOG = LoggerFactory.getLogger(VertexProjectionSpec.class);

    private GraphTraversal<?, ?> graphTraversal;
    private FilterSpec filter;
    private List<String> properties;
    private List<Tuple<String, Order>> sorts;

    private Map<String, EdgeProjectionSpec> edges = new HashMap<>();

    public VertexProjectionSpec() {
        // creates a spec without any starting traversal. NOTE: you'll need to set the traversal
        // via the traversal() method before calling construct!
    }

    public VertexProjectionSpec(GraphTraversalSource s, String label) {
        this.graphTraversal = s.V().hasLabel(label);
    }

    public VertexProjectionSpec traversal(GraphTraversal<?, ?> graphTraversal) {
        this.graphTraversal = graphTraversal;
        return this;
    }

    public VertexProjectionSpec filter(FilterSpec filter) {
        this.filter = filter;
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

    public VertexProjectionSpec sort(List<Tuple<String, Order>> sorts) {
        this.sorts = sorts;
        return this;
    }

    public VertexProjectionSpec sort(Tuple<String, Order>... sorts) {
        this.sorts = List.of(sorts);
        return this;
    }

    public VertexProjectionSpec edge(String projectionKey, EdgeProjectionSpec projectionSpec) {
        edges.put(projectionKey, projectionSpec);
        return this;
    }

    public GraphTraversal<?, org.ntrloc.graph.db.projector.VertexProjection> construct() {
        var baseTraversal = (graphTraversal != null) ? graphTraversal : __.start();

        if (filter != null)  {
            baseTraversal = filter.apply(baseTraversal);
        }

        if (sorts != null) {
            baseTraversal = baseTraversal.order();
            for (Tuple<String, Order> sort : sorts) {
                baseTraversal = baseTraversal.by(sort.first(), sort.second());
            }
        }

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
                finalProperties.putAll(props);
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

package org.ntrloc.graph.db.traversal.projector;

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

    private VertexProjectionSpec vertexProjectionSpec;

    public EdgeProjectionSpec(VertexProjectionSpec vertexProjectionSpec) {
        this.vertexProjectionSpec = vertexProjectionSpec;
    }

    public GraphTraversal<?, List<EdgeProjection>> construct(Direction direction, GraphTraversal<?, ?> baseTraversal) {

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
            return new EdgeProjection(
                    (String) map.get("label"),
                    direction,
                    map.get("id"),
                    getSimplifiedProperties((Map) map.get("properties")),
                    (VertexProjection) map.get("target")
            );
        }).fold();

    }

}

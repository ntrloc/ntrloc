package org.ntrloc.graph.db.projector;

import org.apache.tinkerpop.gremlin.structure.Direction;

import java.util.Map;
import java.util.StringJoiner;

public class EdgeProjection extends Projection {

    private Direction direction;

    private VertexProjection target;

    public EdgeProjection(String label, Direction direction, Object id, Map<String, Object> properties, VertexProjection target) {
        super(label, id, properties);
        this.direction = direction;
        this.target = target;
    }

    public VertexProjection getTarget() {
        return target;
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", org.ntrloc.graph.db.projector.EdgeProjection.class.getSimpleName() + "[", "]")
                .add("id=" + id)
                .add("direction=" + direction)
                .add("label='" + label + "'");
        if (properties != null && !properties.isEmpty()) {
            joiner = joiner.add("properties=" + properties);
        }
        joiner = joiner.add("target=" + target);

        return joiner.toString();
    }

}


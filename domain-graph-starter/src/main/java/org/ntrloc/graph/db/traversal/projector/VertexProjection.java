package org.ntrloc.graph.db.traversal.projector;

import java.util.Map;
import java.util.StringJoiner;

public class VertexProjection extends Projection {

    public VertexProjection(String label, Object id, Map<String, Object> properties) {
        super(label, id, properties);
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", VertexProjection.class.getSimpleName() + "[", "]")
                .add("id=" + id)
                .add("label='" + label + "'");

        if (properties != null && !properties.isEmpty()) {
            joiner = joiner.add("properties=" + properties);
        }

        return joiner.toString();
    }

}

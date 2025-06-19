package org.ntrloc.graph.db.traversal;

import java.util.Map;
import java.util.StringJoiner;

public class EdgeProjection {

    private String label;
    private Object id;
    private Map<String, Object> properties;
    private VertexProjection target;

    public EdgeProjection(String label, Object id, Map<String, Object> properties, VertexProjection target) {
        this.label = label;
        this.id = id;
        this.properties = properties;
        this.target = target;
    }

    public String getLabel() {
        return label;
    }

    public Object getId() {
        return id;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public VertexProjection getTarget() {
        return target;
    }

    @Override
    public String toString() {
        var joiner = new StringJoiner(", ", EdgeProjection.class.getSimpleName() + "[", "]")
                .add("label='" + label + "'")
                .add("id=" + id);

        if (properties != null && !properties.isEmpty()) {
            joiner = joiner.add("properties=" + properties);
        }

        if (target != null) {
            joiner = joiner.add("target=" + target);
        }

        return joiner.toString();
    }

}

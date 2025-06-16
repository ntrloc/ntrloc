package org.nterloc.graph.db.traversal;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

class VertexProjection {

    private String label;
    private Object id;
    private Map<String, Object> properties;
    private Map<String, List<EdgeProjection>> outbound;
    private Map<String, List<EdgeProjection>> inbound;

    public VertexProjection(String label, Object id, Map<String, Object> properties) {
        this.label = label;
        this.id = id;
        this.properties = properties;
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

    public String stringProperty(String name) {
        Object property = properties.get(name);
        if (property == null) {
            return null;
        } else if (property instanceof String) {
            return (String) property;
        } else if (property instanceof List) {
            return ((List<String>) property).get(0);
        } else {
            throw new RuntimeException("Property " + name + " cannot be coerced to a string: " + property.getClass().getSimpleName());
        }
    }

    public Integer intProperty(String name) {
        Object property = properties.get(name);
        if (property == null) {
            return null;
        } else if (property instanceof Integer) {
            return (Integer) property;
        } else if (property instanceof List) {
            return ((List<Integer>) property).get(0);
        } else {
            throw new RuntimeException("Property " + name + " cannot be coerced to an integer: " + property.getClass().getSimpleName());
        }
    }

    public Map<String, List<EdgeProjection>> getOutboundEdges() {
        return outbound;
    }

    public List<EdgeProjection> getOutboundEdges(String name) {
        return outbound.get(name);
    }

    public void setOutboundEdges(Map<String, List<EdgeProjection>> outbound) {
        this.outbound = outbound;
    }

    public boolean hasOutboundEdges() {
        return outbound != null && !outbound.isEmpty() && outbound.values().stream().noneMatch(List::isEmpty);
    }

    public void setInboundEdges(Map<String, List<EdgeProjection>> inbound) {
        this.inbound = inbound;
    }

    public Map<String, List<EdgeProjection>> getInboundEdges() {
        return inbound;
    }

    public List<EdgeProjection> getInboundEdges(String name) {
        return inbound.get(name);
    }

    public boolean hasInboundEdges() {
        return inbound != null && !inbound.isEmpty() && inbound.values().stream().noneMatch(List::isEmpty);
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", VertexProjection.class.getSimpleName() + "[", "]")
                .add("id=" + id)
                .add("label='" + label + "'");

        if (properties != null && !properties.isEmpty()) {
            joiner = joiner.add("properties=" + properties);
        }

        if (hasOutboundEdges()) {
            joiner = joiner.add("outbound=" + outbound);
        }

        if (hasInboundEdges()) {
            joiner = joiner.add("inbound=" + inbound);
        }

        return joiner.toString();
    }

}

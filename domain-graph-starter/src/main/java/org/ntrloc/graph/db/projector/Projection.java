package org.ntrloc.graph.db.projector;

import java.util.List;
import java.util.Map;

public abstract class Projection {

    protected String label;
    protected Object id;
    protected Map<String, Object> properties;

    public Projection(String label, Object id, Map<String, Object> properties) {
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

    public List<Projection> projectionProperty(String name) {
        Object property = properties.get(name);
        if (property == null) {
            return null;
        } else if (property instanceof List) {
            return (List) property;
        } else {
            throw new RuntimeException("Property " + name + " cannot be coerced to an integer: " + property.getClass().getSimpleName());
        }
    }


}

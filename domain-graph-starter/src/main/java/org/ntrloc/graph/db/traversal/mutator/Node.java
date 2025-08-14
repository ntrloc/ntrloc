package org.ntrloc.graph.db.traversal.mutator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public class Node {

    protected Object id;
    protected String label;
    protected Map<String, Object> properties;

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = flattenProperties(properties);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", Node.class.getSimpleName() + "[", "]")
                .add("id=" + id)
                .add("label='" + label + "'")
                .add("properties=" + properties)
                .toString();
    }

    private Map<String, Object> flattenProperties(Map<String, Object> props) {
        Map<String, Object> flattened = new HashMap<>();
        for (var entry : props.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof List list) {
                if (list.size() == 1) {
                    flattened.put(key, list.get(0));
                } else {
                    flattened.put(key, list);
                }
            } else {
                flattened.put(key, value);
            }
        }
        return flattened;
    }

}

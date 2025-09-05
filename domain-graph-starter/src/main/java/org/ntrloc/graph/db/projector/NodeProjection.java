package org.ntrloc.graph.db.projector;

import java.util.Map;
import java.util.StringJoiner;

public class NodeProjection {

    private String id;
    private String nodeType;

    private Map<String, Object> properties;

    public String getId() {
        return id;
    }

    void setId(String id) {
        this.id = id;
    }

    public String getNodeType() {
        return nodeType;
    }

    void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", NodeProjection.class.getSimpleName() + "[", "]")
                .add("id='" + id + "'")
                .add("nodeType='" + nodeType + "'")
                .add("properties=" + properties)
                .toString();
    }
}

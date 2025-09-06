package org.ntrloc.graph.db.projector;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public class ItemProjection {

    private String id;
    private String nodeType;

    private Map<String, Object> properties;
    private Map<String, List<LinkProjection>> links;

    ItemProjection() {
        // no-op
    }

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

    public Map<String, List<LinkProjection>> getLinks() {
        return links;
    }

    public void setLinks(Map<String, List<LinkProjection>> links) {
        this.links = links;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", ItemProjection.class.getSimpleName() + "[", "]")
                .add("id='" + id + "'")
                .add("nodeType='" + nodeType + "'")
                .add("properties=" + properties)
                .add("links=" + links)
                .toString();
    }
}

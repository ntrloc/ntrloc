package org.ntrloc.graph.db.language.projection;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public class ItemProjection {

    private String id;
    private String nodeType;

    private Map<String, Object> properties;
    private Map<String, List<LinkProjection>> links;

    public ItemProjection() {
        // no-op
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
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

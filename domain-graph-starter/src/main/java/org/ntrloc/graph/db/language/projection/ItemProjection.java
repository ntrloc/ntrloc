package org.ntrloc.graph.db.language.projection;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public class ItemProjection {

    private String id;
    private Integer version;
    private boolean isLatestVersion;
    private String itemType;

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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public boolean isLatestVersion() {
        return isLatestVersion;
    }

    public void setLatestVersion(boolean latestVersion) {
        isLatestVersion = latestVersion;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String nodeType) {
        this.itemType = nodeType;
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
                .add("itemType='" + itemType + "'")
                .add("properties=" + properties)
                .add("links=" + links)
                .toString();
    }
}

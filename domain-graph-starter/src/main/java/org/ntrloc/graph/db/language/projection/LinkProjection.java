package org.ntrloc.graph.db.language.projection;

import java.util.Map;

public abstract class LinkProjection {

    private String linkType;

    LinkProjection() {
        // no-op
    }

    protected Map<String, Object> properties;

    public Map<String, Object> getProperties() {
        return properties;
    }

    public String getLinkType() {
        return linkType;
    }

    public void setLinkType(String linkType) {
        this.linkType = linkType;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

}

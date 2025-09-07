package org.ntrloc.graph.db.language.projection;

import java.util.Map;

public abstract class LinkProjection {

    LinkProjection() {
        // no-op
    }

    protected Map<String, Object> properties;

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

}

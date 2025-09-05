package org.ntrloc.graph.db.projector;

import java.util.Map;

public class LinkProjection {

    private Map<String, Object> properties;
    private NodeProjection target;

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    public NodeProjection getTarget() {
        return target;
    }

    public void setTarget(NodeProjection target) {
        this.target = target;
    }

}

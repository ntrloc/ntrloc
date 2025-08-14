package org.ntrloc.graph.db.traversal.pathfinder;

import java.util.Map;

public class PathItem {

    protected Object id;
    protected String label;
    protected Map<String, Object> properties;

    public PathItem(Object id, String label) {
        this.id = id;
        this.label = label;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }

    public Object getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }


}

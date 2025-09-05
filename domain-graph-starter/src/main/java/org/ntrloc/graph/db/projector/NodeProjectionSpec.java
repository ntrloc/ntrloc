package org.ntrloc.graph.db.projector;

import java.util.List;
import java.util.Map;

public class NodeProjectionSpec {

    private NodeSelector nodeSelector;
    private List<String> properties;
    private Map<String, LinkProjectionSpec> links;

    public NodeProjectionSpec() {
        // no-op
    }

    public NodeProjectionSpec(NodeSelector nodeSelector) {
        this.nodeSelector = nodeSelector;
    }

    public NodeSelector getNodeSelector() {
        return nodeSelector;
    }

    public void setNodeSelector(NodeSelector nodeSelector) {
        this.nodeSelector = nodeSelector;
    }

    public NodeProjectionSpec properties(List<String> properties) {
        this.properties = properties;
        return this;
    }

    public List<String> getProperties() {
        return properties;
    }

    public void setProperties(List<String> properties) {
        this.properties = properties;
    }

    public NodeProjectionSpec links(Map<String, LinkProjectionSpec> links) {
        this.links = links;
        return this;
    }

    public Map<String, LinkProjectionSpec> getLinks() {
        return links;
    }

    public void setLinks(Map<String, LinkProjectionSpec> links) {
        this.links = links;
    }
}

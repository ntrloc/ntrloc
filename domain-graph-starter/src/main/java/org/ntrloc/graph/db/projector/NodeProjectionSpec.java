package org.ntrloc.graph.db.projector;

import java.util.List;

public class NodeProjectionSpec {

    private NodeSelector nodeSelector;
    private List<String> properties;

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

}

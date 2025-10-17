package org.ntrloc.graph.db.language;

public class BinaryReferenceProperty implements NodeProperty {

    private String name;
    private String nodeId;

    public BinaryReferenceProperty(String name, String nodeId) {
        this.name = name;
        this.nodeId = nodeId;
    }

    @Override
    public String getName() {
        return name;
    }

    public String getNodeId() {
        return nodeId;
    }

    @Override
    public NodeProperty renamedTo(String name) {
        return new BinaryReferenceProperty(name, nodeId);
    }

}

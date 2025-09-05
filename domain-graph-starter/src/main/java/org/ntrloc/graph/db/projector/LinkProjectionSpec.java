package org.ntrloc.graph.db.projector;

public class LinkProjectionSpec {

    private NodeProjectionSpec targetProjection;

    public NodeProjectionSpec getTargetProjection() {
        return targetProjection;
    }

    public void setTargetProjection(NodeProjectionSpec targetProjection) {
        this.targetProjection = targetProjection;
    }

}

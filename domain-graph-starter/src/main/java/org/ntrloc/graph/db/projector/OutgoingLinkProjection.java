package org.ntrloc.graph.db.projector;

import java.util.StringJoiner;

public class OutgoingLinkProjection extends LinkProjection {

    private NodeProjection target;

    public NodeProjection getTarget() {
        return target;
    }

    public void setTarget(NodeProjection target) {
        this.target = target;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", OutgoingLinkProjection.class.getSimpleName() + "[", "]")
                .add("properties=" + properties)
                .add("target=" + target)
                .toString();
    }
}

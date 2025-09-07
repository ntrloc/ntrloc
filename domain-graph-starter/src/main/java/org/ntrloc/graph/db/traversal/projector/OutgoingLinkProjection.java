package org.ntrloc.graph.db.traversal.projector;

import java.util.StringJoiner;

public class OutgoingLinkProjection extends LinkProjection {

    private ItemProjection target;

    OutgoingLinkProjection() {
        // no-op
    }

    public ItemProjection getTarget() {
        return target;
    }

    public void setTarget(ItemProjection target) {
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

package org.ntrloc.graph.db.traversal.projector;

import java.util.StringJoiner;

public class IncomingLinkProjection extends LinkProjection {

    private ItemProjection source;

    public ItemProjection getSource() {
        return source;
    }

    IncomingLinkProjection() {
        // no-op
    }

    public void setSource(ItemProjection source) {
        this.source = source;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", IncomingLinkProjection.class.getSimpleName() + "[", "]")
                .add("properties=" + properties)
                .add("source=" + source)
                .toString();
    }
}

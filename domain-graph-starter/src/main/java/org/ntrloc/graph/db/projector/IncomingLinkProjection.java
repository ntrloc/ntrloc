package org.ntrloc.graph.db.projector;

import java.util.StringJoiner;

public class IncomingLinkProjection extends LinkProjection {

    private NodeProjection source;

    public NodeProjection getSource() {
        return source;
    }

    public void setSource(NodeProjection source) {
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

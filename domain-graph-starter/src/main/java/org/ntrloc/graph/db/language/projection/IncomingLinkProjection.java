package org.ntrloc.graph.db.language.projection;

import java.util.StringJoiner;

public class IncomingLinkProjection extends LinkProjection {

    private ItemProjection source;

    public ItemProjection getSource() {
        return source;
    }

    public IncomingLinkProjection() {
        // no-op
    }

    public void setSource(ItemProjection source) {
        this.source = source;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", IncomingLinkProjection.class.getSimpleName() + "[", "]")
                .add("id=" + id)
                .add("linkType=" + linkType)
                .add("properties=" + properties)
                .add("source=" + source)
                .toString();
    }
}

package org.ntrloc.graph.db.traversal.mutator;

import java.util.StringJoiner;

public class Revision extends Node {

    Node revisionOf;

    public Node getRevisionOf() {
        return revisionOf;
    }

    public void setRevisionOf(Node revisionOf) {
        this.revisionOf = revisionOf;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", Revision.class.getSimpleName() + "[", "]")
                .add("revisionOf=" + revisionOf)
                .add("id=" + id)
                .add("label='" + label + "'")
                .add("properties=" + properties)
                .toString();
    }
}

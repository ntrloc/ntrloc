package org.ntrloc.graph.db.traversal.mutator;

import org.ntrloc.graph.db.PropertyConstants;

import java.util.StringJoiner;

public class Revision extends Node {

    enum RevisionType {
        UPDATE,
        DELETE
    }

    Node revisionOf;

    public Node getRevisionOf() {
        return revisionOf;
    }

    public void setRevisionOf(Node revisionOf) {
        this.revisionOf = revisionOf;
    }

    public RevisionType getType() {
        var type = (String) properties.get(PropertyConstants.REVISION_TYPE_PROPERTY);
        if (type == null) {
            return null;
        } else {
            return RevisionType.valueOf(type);
        }
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", Revision.class.getSimpleName() + "[", "]")
                .add("revisionOf=" + revisionOf)
                .add("type=" + getType())
                .add("id=" + id)
                .add("label='" + label + "'")
                .add("properties=" + properties)
                .toString();
    }
}

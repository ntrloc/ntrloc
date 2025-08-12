package org.ntrloc.graph.db.pathfinder;

public class PathNode extends PathItem {

    public PathLink next;

    public PathNode(Object id, String label) {
        super(id, label);
    }

    public void setNext(PathLink next) {
        this.next = next;
    }

    public PathLink getNext() {
        return next;
    }

}

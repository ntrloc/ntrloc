package org.ntrloc.graph.db.traversal.pathfinder;

public class PathLink extends PathItem {

    enum Direction {
        IN,
        OUT
    }

    public PathNode next;
    public Direction direction;

    public PathLink(Object id, String label) {
        super(id, label);
    }

    public void setNext(PathNode next) {
        this.next = next;
    }

    public PathNode getNext() {
        return next;
    }

    public Direction getDirection() {
        return direction;
    }
    public void setDirection(Direction direction) {
        this.direction = direction;
    }

}

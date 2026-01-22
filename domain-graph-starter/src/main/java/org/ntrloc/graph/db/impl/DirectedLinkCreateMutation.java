package org.ntrloc.graph.db.impl;

import org.apache.tinkerpop.gremlin.structure.Direction;
import org.ntrloc.graph.db.language.mutation.LinkCreateMutation;

public class DirectedLinkCreateMutation extends LinkCreateMutation {

    private Direction direction;

    public DirectedLinkCreateMutation(LinkCreateMutation mutation, Direction direction) {
        setLinkType(mutation.getLinkType());
        setProperties(mutation.getProperties());
        setSelector(mutation.getSelector());
        setDirection(direction);
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }
}

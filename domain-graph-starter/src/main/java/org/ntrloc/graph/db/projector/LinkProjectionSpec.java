package org.ntrloc.graph.db.projector;

import org.apache.tinkerpop.gremlin.structure.Direction;

import java.util.List;

public class LinkProjectionSpec {

    private String linkName;
    private Direction direction;
    private List<String> properties;
    private NodeProjectionSpec targetProjection;

    public LinkProjectionSpec(String linkName, Direction direction) {
        this.linkName = linkName;
        this.direction = direction;
    }

    public String getLinkName() {
        return linkName;
    }

    public Direction getDirection() {
        return direction;
    }

    public LinkProjectionSpec properties(List<String> properties) {
        this.properties = properties;
        return this;
    }

    public List<String> getProperties() {
        return properties;
    }

    public void setProperties(List<String> properties) {
        this.properties = properties;
    }

    public LinkProjectionSpec targetProjection(NodeProjectionSpec targetProjection) {
        this.targetProjection = targetProjection;
        return this;
    }

    public NodeProjectionSpec getTargetProjection() {
        return targetProjection;
    }

    public void setTargetProjection(NodeProjectionSpec targetProjection) {
        this.targetProjection = targetProjection;
    }

}

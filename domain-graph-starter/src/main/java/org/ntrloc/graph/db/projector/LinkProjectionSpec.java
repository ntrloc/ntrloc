package org.ntrloc.graph.db.projector;

import org.apache.tinkerpop.gremlin.structure.Direction;

import java.util.List;

public class LinkProjectionSpec {

    private String linkName;
    private Direction direction;
    private String nodeLabel;
    private List<String> properties;
    private NodeProjectionSpec nodeProjection;

    /**
     * Specifies the information to return for a link between items in the graph.
     * @param linkName the name of the link
     * @param direction the direction of the link
     * @param nodeLabel the label of the item on the "other side" of the link
     */
    public LinkProjectionSpec(String linkName, Direction direction, String nodeLabel) {
        this.linkName = linkName;
        this.direction = direction;
        this.nodeLabel = nodeLabel;
    }

    public String getLinkName() {
        return linkName;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getNodeLabel() {
        return nodeLabel;
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

    public LinkProjectionSpec nodeProjection(NodeProjectionSpec nodeProjection) {
        this.nodeProjection = nodeProjection;
        return this;
    }

    public NodeProjectionSpec getNodeProjection() {
        return nodeProjection;
    }


}

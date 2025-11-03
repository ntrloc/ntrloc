package org.ntrloc.graph.db.language.projection;

import org.apache.tinkerpop.gremlin.structure.Direction;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

public class LinkProjectionSpec {

    private String linkLabel;
    private Direction direction;
    private List<String> properties;
    private ItemProjectionSpec itemProjectionSpec;

    /**
     * Specifies the information to return for a link between items in the graph.
     * @param linkLabel the label of the link
     * @param direction the direction of the link
     */
    public LinkProjectionSpec(String linkLabel, Direction direction) {
        this.linkLabel = linkLabel;
        this.direction = direction;
    }

    public LinkProjectionSpec(String linkLabel, Direction direction, ItemProjectionSpec itemProjectionSpec) {
        this(linkLabel, direction);
        this.itemProjectionSpec = itemProjectionSpec;
    }

    public String getLinkLabel() {
        return linkLabel;
    }

    public void setLinkLabel(String linkLabel) {
        this.linkLabel = linkLabel;
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

    public LinkProjectionSpec itemProjection(ItemProjectionSpec nodeProjection) {
        this.itemProjectionSpec = nodeProjection;
        return this;
    }

    public void setItemProjectionSpec(ItemProjectionSpec itemProjectionSpec) {
        this.itemProjectionSpec = itemProjectionSpec;
    }

    public ItemProjectionSpec getItemProjectionSpec() {
        if (itemProjectionSpec == null) {
            return new ItemProjectionSpec();
        } else {
            return itemProjectionSpec;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LinkProjectionSpec that)) return false;
        return Objects.equals(linkLabel, that.linkLabel) && direction == that.direction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(linkLabel, direction);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", LinkProjectionSpec.class.getSimpleName() + "[", "]")
                .add("linkLabel='" + linkLabel + "'")
                .add("direction=" + direction)
                .add("properties=" + properties)
                .add("itemProjectionSpec=" + itemProjectionSpec)
                .toString();
    }
}

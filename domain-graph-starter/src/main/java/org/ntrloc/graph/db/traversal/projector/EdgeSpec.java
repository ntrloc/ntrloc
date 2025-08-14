package org.ntrloc.graph.db.traversal.projector;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.ntrloc.graph.db.traversal.projector.filter.FilterSpec;

import java.util.List;

public class EdgeSpec {

    private String edgeLabel;

    private String vertexLabel;

    private Direction direction;

    private boolean required;

    private List<EdgeSort> sorts;

    protected FilterSpec filter;

    private EdgeProjectionSpec projectionSpec;

    public EdgeSpec(String edgeLabel, Direction direction, String vertexLabel) {
        this(edgeLabel, direction, vertexLabel, false);
    }

    public EdgeSpec(String edgeLabel, Direction direction, String vertexLabel, boolean required) {
        this.edgeLabel = edgeLabel;
        this.direction = direction;
        this.vertexLabel = vertexLabel;
        this.required = required;
    }

    public boolean isRequired() {
        return required;
    }

    public String getEdgeLabel() {
        return edgeLabel;
    }

    public String getVertexLabel() {
        return vertexLabel;
    }

    public Direction getDirection() {
        return direction;
    }

    public EdgeSpec sort(List<EdgeSort> sorts) {
        this.sorts = sorts;
        return this;
    }

    public EdgeSpec sort(EdgeSort... sorts) {
        this.sorts = List.of(sorts);
        return this;
    }

    public EdgeSpec filter(FilterSpec filter) {
        this.filter = filter;
        return this;
    }

    public EdgeSpec projection(VertexProjectionSpec vertexProjectionSpec) {
        if (projectionSpec == null) {
            projectionSpec = new EdgeProjectionSpec(vertexProjectionSpec);
        }
        return this;
    }

    public GraphTraversal<?, List<EdgeProjection>> construct() {
        GraphTraversal<?, ?> baseTraversal = switch(direction) {
            case Direction.IN -> __.inE(edgeLabel).where(__.outV().hasLabel(vertexLabel));
            case Direction.OUT -> __.outE(edgeLabel).where(__.inV().hasLabel(vertexLabel));
            default -> throw new IllegalArgumentException("Invalid direction: " + direction);
        };

        if (filter != null)  {
            baseTraversal = baseTraversal.where(filter.build());
        }

        if (sorts != null) {
            baseTraversal = baseTraversal.order();
            for (EdgeSort sort : sorts) {
                if (sort.getSource().equals(Source.EDGE)) {
                    baseTraversal = baseTraversal.by(sort.getPropertyName(), sort.getOrder());
                } else if (sort.getSource().equals(Source.VERTEX)) {
                    if (direction == Direction.IN) {
                        baseTraversal = baseTraversal.by(__.outV().values(sort.getPropertyName()), sort.getOrder());
                    } else {
                        baseTraversal = baseTraversal.by(__.inV().values(sort.getPropertyName()), sort.getOrder());
                    }
                } else {
                    throw new IllegalArgumentException("Invalid source: " + sort.getSource());
                }

            }
        }

        GraphTraversal<?, List<EdgeProjection>> projectionTraversal = projectionSpec.construct(direction, baseTraversal);
        if (required) {
            return projectionTraversal.filter(trav -> !trav.get().isEmpty());
        } else {
            return projectionTraversal;
        }
    }

}

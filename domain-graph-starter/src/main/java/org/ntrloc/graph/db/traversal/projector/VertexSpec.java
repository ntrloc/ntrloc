package org.ntrloc.graph.db.traversal.projector;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.ntrloc.graph.db.traversal.projector.filter.FilterSpec;

import java.util.List;

public class VertexSpec {

    private GraphTraversal<?, ?> graphTraversal;

    private List<VertexSort> sorts;

    protected FilterSpec filter;

    private VertexProjectionSpec projectionSpec;

    public VertexSpec(GraphTraversalSource s, String label) {
        this.graphTraversal = s.V().hasLabel(label);
    }

    public VertexSpec sort(List<VertexSort> sorts) {
        this.sorts = sorts;
        return this;
    }

    public VertexSpec sort(VertexSort... sorts) {
        this.sorts = List.of(sorts);
        return this;
    }

    public VertexSpec filter(FilterSpec filter) {
        this.filter = filter;
        return this;
    }

    public VertexProjectionSpec projection() {
        if (projectionSpec == null) {
            projectionSpec = new VertexProjectionSpec();
        }
        return projectionSpec;
    }


    public GraphTraversal<?, VertexProjection> construct() {
        var baseTraversal = (graphTraversal != null) ? graphTraversal : __.start();

        if (filter != null)  {
            baseTraversal = baseTraversal.where(filter.build());
        }

        if (sorts != null) {
            baseTraversal = baseTraversal.order();
            for (VertexSort sort : sorts) {
                baseTraversal = baseTraversal.by(sort.getPropertyName(), sort.getOrder());
            }
        }

        projectionSpec = projectionSpec.traversal(baseTraversal);
        return projection().construct();
    }

}

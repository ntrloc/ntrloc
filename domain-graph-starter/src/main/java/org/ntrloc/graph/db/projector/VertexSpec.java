package org.ntrloc.graph.db.projector;

import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.projector.filter.FilterSpec;

import java.util.List;

public class VertexSpec {

    private GraphTraversal<?, ?> graphTraversal;

    private List<Tuple<String, Order>> sorts;

    protected FilterSpec filter;

    private VertexProjectionSpec projectionSpec;

    public VertexSpec(GraphTraversalSource s, String label) {
        this.graphTraversal = s.V().hasLabel(label);
    }

    public VertexSpec sort(List<Tuple<String, Order>> sorts) {
        this.sorts = sorts;
        return this;
    }

    public VertexSpec sort(Tuple<String, Order>... sorts) {
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


    public GraphTraversal<?, org.ntrloc.graph.db.projector.VertexProjection> construct() {
        var baseTraversal = (graphTraversal != null) ? graphTraversal : __.start();

        if (filter != null)  {
            baseTraversal = filter.apply(baseTraversal);
        }

        if (sorts != null) {
            baseTraversal = baseTraversal.order();
            for (Tuple<String, Order> sort : sorts) {
                baseTraversal = baseTraversal.by(sort.first(), sort.second());
            }
        }

        projectionSpec = projectionSpec.traversal(baseTraversal);
        return projection().construct();
    }

}

package org.ntrloc.graph.db.projector.filter;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.ArrayList;
import java.util.List;

public class OrFilterSpec implements FilterSpec {

    private List<FilterSpec> filters;

    public OrFilterSpec(FilterSpec... filters) {
        this.filters = filters.length == 0 ? null : List.of(filters);
    }

    public List<FilterSpec> getFilters() {
        return filters;
    }

    @Override
    public GraphTraversal<?, ?> apply(GraphTraversal<?, ?> traversal) {
        List<GraphTraversal<?, ?>> travs = new ArrayList<>();
        for (FilterSpec filter : filters) {
            travs.add(filter.apply(__.start()));
        }
        return traversal.or(travs.toArray(GraphTraversal[]::new));
    }

}

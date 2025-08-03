package org.ntrloc.graph.db.projector.filter;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AndFilterSpec implements FilterSpec {

    private List<FilterSpec> filters;

    public AndFilterSpec(FilterSpec... filters) {
        this.filters = Arrays.stream(filters).toList();
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

        return traversal.and(travs.toArray(GraphTraversal[]::new));
    }

}

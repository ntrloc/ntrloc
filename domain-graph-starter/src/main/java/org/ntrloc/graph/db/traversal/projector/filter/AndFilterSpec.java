package org.ntrloc.graph.db.traversal.projector.filter;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AndFilterSpec implements FilterSpec {

    private List<FilterSpec> filters;

    public static AndFilterSpec with(FilterSpec... filters) {
        return new AndFilterSpec(filters);
    }

    public AndFilterSpec(FilterSpec... filters) {
        this.filters = Arrays.stream(filters).toList();
    }

    public List<FilterSpec> getFilters() {
        return filters;
    }

    @Override
    public GraphTraversal<?, ?> build() {
        List<GraphTraversal<?, ?>> travs = new ArrayList<>();
        for (FilterSpec filter : filters) {
            travs.add(filter.build());

        }
        return __.and(travs.toArray(GraphTraversal[]::new));
    }

}

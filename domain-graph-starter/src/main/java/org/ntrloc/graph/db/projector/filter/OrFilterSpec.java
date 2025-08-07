package org.ntrloc.graph.db.projector.filter;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

import java.util.ArrayList;
import java.util.List;

public class OrFilterSpec implements FilterSpec {

    private List<FilterSpec> filters;

    public static OrFilterSpec with(FilterSpec... filters) {
        return new OrFilterSpec(filters);
    }

    public OrFilterSpec(FilterSpec... filters) {
        this.filters = filters.length == 0 ? null : List.of(filters);
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
        return __.or(travs.toArray(GraphTraversal[]::new));
    }

}

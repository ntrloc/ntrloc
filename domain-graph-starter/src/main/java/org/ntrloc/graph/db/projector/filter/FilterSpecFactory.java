package org.ntrloc.graph.db.projector.filter;

import org.apache.tinkerpop.gremlin.process.traversal.P;

public class FilterSpecFactory {

    public static PropertyFilterSpec has(String propertyName) {
        return new PropertyFilterSpec(propertyName);
    }

    public static PropertyPredicateFilterSpec hasValue(String propertyName, Object value) {
        return new PropertyPredicateFilterSpec(propertyName, value);
    }

    public static PropertyPredicateFilterSpec hasPredicate(String propertyName, P predicate) {
        return new PropertyPredicateFilterSpec(propertyName, predicate);
    }

    public static AndFilterSpec and(FilterSpec... filters) {
        return new AndFilterSpec(filters);
    }

    public static OrFilterSpec or(FilterSpec... filters) {
        return new OrFilterSpec(filters);
    }

}

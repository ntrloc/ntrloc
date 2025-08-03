package org.ntrloc.graph.db.projector.filter;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;

import java.util.function.Predicate;

public class PropertyPredicateFilterSpec implements FilterSpec {

    private String propertyName;
    private P<?> predicate;

    public PropertyPredicateFilterSpec(String propertyName, Object value) {
        this(propertyName, P.eq(value));
    }

    public PropertyPredicateFilterSpec(String propertyName, P<?> value) {
        this.propertyName = propertyName;
        this.predicate = value;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public Predicate getPredicate() {
        return predicate;
    }

    @Override
    public GraphTraversal<?, ?> apply(GraphTraversal<?, ?> traversal) {
        return traversal.has(propertyName, predicate);
    }

}

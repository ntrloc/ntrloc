package org.ntrloc.graph.db.traversal.projector.filter;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;

import java.util.function.Predicate;

public class PropertyPredicateFilterSpec extends ExplicitTraversalFilterSpec {

    private String propertyName;
    private P<?> predicate;

    public static PropertyPredicateFilterSpec with(String propertyName, Object value) {
        return new PropertyPredicateFilterSpec(propertyName, value);
    }

    public static PropertyPredicateFilterSpec with(String propertyName, P predicate) {
        return new PropertyPredicateFilterSpec(propertyName, predicate);
    }

    public static PropertyPredicateFilterSpec with(GraphTraversal<?, ?> traversal, String propertyName, Object value) {
        return new PropertyPredicateFilterSpec(traversal, propertyName, value);
    }

    public static PropertyPredicateFilterSpec with(GraphTraversal<?, ?> traversal, String propertyName, P<?> value) {
        return new PropertyPredicateFilterSpec(traversal, propertyName, value);
    }

    public PropertyPredicateFilterSpec(String propertyName, Object value) {
        this(propertyName, P.eq(value));
    }

    public PropertyPredicateFilterSpec(String propertyName, P<?> value) {
        this.propertyName = propertyName;
        this.predicate = value;
    }

    public PropertyPredicateFilterSpec(GraphTraversal<?, ?> traversal, String propertyName, Object value) {
        this(propertyName, value);
        this.traversal = traversal;
    }

    public PropertyPredicateFilterSpec(GraphTraversal<?, ?> traversal, String propertyName, P<?> value) {
        this(propertyName, value);
        this.traversal = traversal;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public Predicate getPredicate() {
        return predicate;
    }

    @Override
    public GraphTraversal<?, ?> build() {
        return getTraversal().has(propertyName, predicate);
    }

}

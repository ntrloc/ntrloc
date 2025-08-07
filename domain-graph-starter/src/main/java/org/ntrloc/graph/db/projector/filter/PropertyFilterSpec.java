package org.ntrloc.graph.db.projector.filter;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;

public class PropertyFilterSpec extends ExplicitTraversalFilterSpec {

    private final String propertyName;

    public static PropertyFilterSpec on(String propertyName) {
        return new PropertyFilterSpec(propertyName);
    }

    public PropertyFilterSpec(String propertyName) {
        this.propertyName = propertyName;
    }

    public PropertyFilterSpec(String propertyName, GraphTraversal<?, ?> traversal) {
        this.propertyName = propertyName;
        this.traversal = traversal;
    }

    public String getPropertyName() {
        return propertyName;
    }

    @Override
    public GraphTraversal<?, ?> build() {
        return getTraversal().has(propertyName);
    }
}

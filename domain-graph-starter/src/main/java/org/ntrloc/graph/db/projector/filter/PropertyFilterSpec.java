package org.ntrloc.graph.db.projector.filter;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;

public class PropertyFilterSpec implements FilterSpec {

    private final String propertyName;

    public PropertyFilterSpec(String propertyName) {
        this.propertyName = propertyName;
    }

    public String getPropertyName() {
        return propertyName;
    }

    @Override
    public GraphTraversal<?, ?> apply(GraphTraversal<?, ?> traversal) {
        return traversal.has(propertyName);
    }
}

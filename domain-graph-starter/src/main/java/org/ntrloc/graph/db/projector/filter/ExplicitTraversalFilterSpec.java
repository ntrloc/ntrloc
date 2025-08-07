package org.ntrloc.graph.db.projector.filter;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;

/**
 * A filter that allows the caller to explictly set the traversal
 * to be used as part of the filtering process
 */
public abstract class ExplicitTraversalFilterSpec implements FilterSpec {

    protected GraphTraversal<?, ?> traversal;

    public GraphTraversal<?, ?> getTraversal() {
        if (traversal == null) {
            return __.start();
        } else {
            return traversal;
        }
    }

    public void setTraversal(GraphTraversal<?, ?> traversal) {
        this.traversal = traversal;
    }

}

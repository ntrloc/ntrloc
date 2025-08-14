package org.ntrloc.graph.db.traversal.projector.filter;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;

public interface FilterSpec {

    GraphTraversal<?, ?> build();

}

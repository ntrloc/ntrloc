package org.nterloc.graph.db.traversal;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.Iterator;

public class Traverser {

    private VertexProjectionSpec vertexProjectionSpec;

    public Traverser(GraphTraversal<Vertex, Vertex> graphTraversal) {
        this.vertexProjectionSpec = new VertexProjectionSpec(graphTraversal);
    }

    public Traverser(VertexProjectionSpec vertexProjectionSpec) {
        this.vertexProjectionSpec = vertexProjectionSpec;
    }

    public Iterator<VertexProjection> iterator() {
        return vertexProjectionSpec.construct();
    }

}
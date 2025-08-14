package org.ntrloc.graph.db.traversal.projector;

import org.apache.tinkerpop.gremlin.process.traversal.Order;

public class VertexSort {

    private String propertyName;
    private Order order;

    public VertexSort(String propertyName, Order order) {
        this.propertyName = propertyName;
        this.order = order;
    }

    public static VertexSort on(String propertyName, Order order) {
        VertexSort sort = new VertexSort(propertyName, order);
        return sort;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public Order getOrder() {
        return order;
    }
}

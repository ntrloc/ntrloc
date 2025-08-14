package org.ntrloc.graph.db.traversal.projector;

import org.apache.tinkerpop.gremlin.process.traversal.Order;

public class EdgeSort {

    private Source source;
    private String propertyName;
    private Order order;

    public EdgeSort(Source source, String propertyName, Order order) {
        this.source = source;
        this.propertyName = propertyName;
        this.order = order;
    }

    public static EdgeSort vertex(String propertyName, Order order) {
        EdgeSort sort = new EdgeSort(Source.VERTEX, propertyName, order);
        return sort;
    }

    public static EdgeSort edge(String propertyName, Order order) {
        EdgeSort sort = new EdgeSort(Source.EDGE, propertyName, order);
        return sort;
    }

    public Source getSource() {
        return source;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public Order getOrder() {
        return order;
    }
}

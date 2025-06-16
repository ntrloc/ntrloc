package org.nterloc.graph.db.mutation;

public interface ScalarProperty<T, R> extends Property<T> {

    String getName();

    R getValue();

}

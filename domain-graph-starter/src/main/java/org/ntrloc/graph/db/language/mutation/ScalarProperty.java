package org.ntrloc.graph.db.language.mutation;

public interface ScalarProperty<T, R> extends Property<T> {

    String getName();

    R getValue();

}

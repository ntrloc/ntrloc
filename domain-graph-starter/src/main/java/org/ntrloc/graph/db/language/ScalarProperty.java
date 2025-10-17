package org.ntrloc.graph.db.language;

public interface ScalarProperty<T, R> extends Property<T> {

    R getValue();

    ScalarProperty<T, R> renamedTo(String name);

}

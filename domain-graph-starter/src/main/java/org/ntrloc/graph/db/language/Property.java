package org.ntrloc.graph.db.language;

public interface Property<T> {

    String getName();

    Property<T> renamedTo(String name);

}

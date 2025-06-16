package org.nterloc.graph.db.mutation;

import java.util.List;

public interface ListProperty<T> extends Property<T> {

    List<T> getValues();

}

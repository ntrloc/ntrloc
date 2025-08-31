package org.ntrloc.graph.db.language;

import java.util.List;

public interface ListProperty<T> extends Property<T> {

    List<T> getValues();

}

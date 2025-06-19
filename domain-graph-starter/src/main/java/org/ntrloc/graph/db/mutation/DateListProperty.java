package org.ntrloc.graph.db.mutation;

import java.util.Date;
import java.util.List;

public class DateListProperty implements ListProperty<Date> {

    private String name;
    private List<Date> values;

    public DateListProperty(String name, List<Date> values) {
        this.name = name;
        this.values = values;
    }

    @Override
    public List<Date> getValues() {
        return values;
    }

    @Override
    public String getName() {
        return name;
    }

}

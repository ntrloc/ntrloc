package org.ntrloc.graph.db.language;

import java.util.Date;
import java.util.List;

public class DateTimeListProperty implements ListProperty<Date> {

    private String name;
    private List<Date> values;

    public DateTimeListProperty(String name, List<Date> values) {
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

    @Override
    public DateTimeListProperty renamedTo(String name) {
        return new DateTimeListProperty(name, values);
    }

}

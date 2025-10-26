package org.ntrloc.graph.db.language;

import java.util.List;

public class DateListProperty implements ListProperty<String> {

    private String name;
    private List<String> values;

    public DateListProperty(String name, List<String> values) {
        this.name = name;
        this.values = values;
    }

    @Override
    public List<String> getValues() {
        return values;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public DateListProperty renamedTo(String name) {
        return new DateListProperty(name, values);
    }

}

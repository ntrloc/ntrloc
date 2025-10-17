package org.ntrloc.graph.db.language;

import java.util.List;

public class BooleanListProperty implements ListProperty<Boolean> {

    private String name;
    private List<Boolean> values;

    public BooleanListProperty(String name, List<Boolean> values) {
        this.name = name;
        this.values = values;
    }

    @Override
    public List<Boolean> getValues() {
        return values;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public BooleanListProperty renamedTo(String name) {
        return new BooleanListProperty(name, values);
    }
}

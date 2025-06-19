package org.ntrloc.graph.db.mutation;

import java.util.List;

public class StringListProperty implements ListProperty<String> {

    private String name;
    private List<String> values;

    public StringListProperty(String name, List<String> values) {
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

}

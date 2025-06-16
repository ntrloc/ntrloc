package org.nterloc.graph.db.mutation;

import java.util.List;

public class IntListProperty implements ListProperty<Integer> {

    private String name;
    private List<Integer> values;

    public IntListProperty(String name, List<Integer> values) {
        this.name = name;
        this.values = values;
    }

    @Override
    public List<Integer> getValues() {
        return values;
    }

    @Override
    public String getName() {
        return name;
    }

}

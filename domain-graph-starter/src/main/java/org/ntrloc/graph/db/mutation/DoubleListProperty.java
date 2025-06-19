package org.ntrloc.graph.db.mutation;

import java.util.List;

public class DoubleListProperty implements ListProperty<Double> {

    private String name;
    private List<Double> values;

    public DoubleListProperty(String name, List<Double> values) {
        this.name = name;
        this.values = values;
    }

    @Override
    public List<Double> getValues() {
        return values;
    }

    @Override
    public String getName() {
        return name;
    }

}

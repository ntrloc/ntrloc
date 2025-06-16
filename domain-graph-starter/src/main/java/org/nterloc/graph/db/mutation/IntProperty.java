package org.nterloc.graph.db.mutation;

public class IntProperty implements ScalarProperty<Integer, Integer> {

    private String name;
    private Integer value;

    public IntProperty(String name, Integer value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Integer getValue() {
        return value;
    }

}

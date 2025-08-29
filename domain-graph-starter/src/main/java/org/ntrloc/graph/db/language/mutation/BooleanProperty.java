package org.ntrloc.graph.db.language.mutation;

public class BooleanProperty implements ScalarProperty<Boolean, Boolean> {

    private String name;
    private Boolean value;

    public BooleanProperty(String name, Boolean value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Boolean getValue() {
        return value;
    }

}

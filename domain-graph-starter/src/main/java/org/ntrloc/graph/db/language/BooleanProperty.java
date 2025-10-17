package org.ntrloc.graph.db.language;

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

    @Override
    public ScalarProperty<Boolean, Boolean> renamedTo(String name) {
        return new BooleanProperty(name, value);
    }

}

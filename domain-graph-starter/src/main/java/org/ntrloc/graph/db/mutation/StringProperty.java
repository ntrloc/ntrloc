package org.ntrloc.graph.db.mutation;

public class StringProperty implements ScalarProperty<String, String> {

    private String value;

    private String name;

    public StringProperty(String name, String value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getValue() {
        return value;
    }

}

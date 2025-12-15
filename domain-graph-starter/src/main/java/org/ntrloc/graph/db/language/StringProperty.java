package org.ntrloc.graph.db.language;

public class StringProperty implements ScalarProperty<String, String> {

    private String value;

    private String name;

    public StringProperty() {
        // no-op for Jackson
    }

    public StringProperty(String name, String value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public ScalarProperty<String, String> renamedTo(String name) {
        return new StringProperty(name, value);
    }

}

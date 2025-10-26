package org.ntrloc.graph.db.language;

public class DateProperty implements ScalarProperty<String, String> {

    private String name;
    private String value;

    public DateProperty(String name, String value) {
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

    @Override
    public ScalarProperty<String, String> renamedTo(String name) {
        return new DateProperty(name, value);
    }

}

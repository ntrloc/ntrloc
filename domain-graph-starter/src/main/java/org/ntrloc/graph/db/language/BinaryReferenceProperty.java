package org.ntrloc.graph.db.language;

public class BinaryReferenceProperty implements ScalarProperty<Long, Long> {

    private String name;
    private Long value;

    public BinaryReferenceProperty(String name, Long value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Long getValue() {
        return value;
    }

}

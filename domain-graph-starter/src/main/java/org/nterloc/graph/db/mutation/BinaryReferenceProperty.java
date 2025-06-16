package org.nterloc.graph.db.mutation;

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

package org.ntrloc.graph.db.language;

public class DoubleProperty implements ScalarProperty<Double, Double> {

    private String name;
    private Double value;

    public DoubleProperty(String name, Double value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Double getValue() {
        return value;
    }

}

package org.nterloc.graph.db.schema;

import java.util.Objects;

public class Cardinality {
    Integer min;
    Integer max;

    public Cardinality(Integer min, Integer max) {
        this.min = min;
        this.max = max;
    }

    public Integer getMin() {
        return min;
    }

    public Integer getMax() {
        return max;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Cardinality that)) return false;
        return Objects.equals(min, that.min) && Objects.equals(max, that.max);
    }

    @Override
    public int hashCode() {
        return Objects.hash(min, max);
    }
}

package org.ntrloc.graph;

import java.util.Objects;

public class Tuple<O1, O2> {

    private final O1 first;
    private final O2 second;

    private Tuple(O1 first, O2 second) {
        this.first = first;
        this.second = second;
    }

    public static <O1, O2> Tuple<O1, O2> of(O1 first, O2 second) {
        return new Tuple<>(first, second);
    }

    public O1 first() {
        return this.first;
    }

    public O2 second() {
        return this.second;
    }

    @Override
    public String toString() {
        return String.format("(%s, %s)", first, second);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Tuple<?, ?> tuple)) return false;
        return Objects.equals(first, tuple.first) && Objects.equals(second, tuple.second);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second);
    }
}

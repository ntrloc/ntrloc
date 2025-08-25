package org.ntrloc.graph;

import java.util.Objects;

public class Tuple<O1, O2> {

    private final O1 _1;
    private final O2 _2;

    private Tuple(O1 o1, O2 o2) {
        this._1 = o1;
        this._2 = o2;
    }

    public static <O1, O2> Tuple of(O1 _1, O2 _2) {
        return new Tuple(_1, _2);
    }

    public O1 first() {
        return this._1;
    }

    public O2 second() {
        return this._2;
    }

    @Override
    public String toString() {
        return String.format("(%s, %s)", _1, _2);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Tuple<?, ?> tuple)) return false;
        return Objects.equals(_1, tuple._1) && Objects.equals(_2, tuple._2);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_1, _2);
    }
}

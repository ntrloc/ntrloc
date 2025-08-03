package org.ntrloc.graph;

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

}

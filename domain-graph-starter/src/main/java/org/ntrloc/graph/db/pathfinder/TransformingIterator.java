package org.ntrloc.graph.db.pathfinder;

import java.util.Iterator;
import java.util.function.Function;

public class TransformingIterator<T, R> implements Iterator<R> {

    private final Iterator<T> source;
    private final Function<T, R> transformer;

    public TransformingIterator(Iterator<T> source, Function<T, R> transformer) {
        this.source = source;
        this.transformer = transformer;
    }

    @Override
    public boolean hasNext() {
        return source.hasNext();
    }

    @Override
    public R next() {
        return transformer.apply(source.next());
    }

}

package org.ntrloc.graph.db.language.selectors.predicate;

/* A predicate that accepts a single value */
public interface UnaryPredicate<T> extends Predicate {

    T getValue();

}

package org.ntrloc.graph.db.projector.selectors.predicate;

/* A predicate that accepts a single value */
public interface UnaryPredicate extends Predicate {

    Object getValue();

}

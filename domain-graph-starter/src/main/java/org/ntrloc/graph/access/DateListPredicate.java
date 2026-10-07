package org.ntrloc.graph.access;

// Mirrors NumberListPredicate -- size/min/max are meaningful for a list of dates; sum/avg aren't, so
// they're omitted rather than included as dead weight.
public sealed interface DateListPredicate extends Predicate
        permits DateListIsEmptyPredicate, DateListIsNotEmptyPredicate, DateListSizePredicate,
        DateListMinPredicate, DateListMaxPredicate {
}

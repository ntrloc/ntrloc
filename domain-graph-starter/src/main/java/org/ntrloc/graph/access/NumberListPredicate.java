package org.ntrloc.graph.access;

// Presence stays split; size and every aggregate function (sum/avg/min/max) reduce to a number, so
// each is a fielded class like NumberPredicate -- not split further per operator, since there's no
// "any"/"all" version of a sum and every numeric comparison on the result is safely interchangeable.
public sealed interface NumberListPredicate extends Predicate
        permits NumberListIsEmptyPredicate, NumberListIsNotEmptyPredicate, NumberListSizePredicate,
        NumberListSumPredicate, NumberListAvgPredicate, NumberListMinPredicate, NumberListMaxPredicate {
}

package org.ntrloc.graph.access;

// Membership/presence operators stay split (same type-safety reasoning as StringPredicate); size
// reduces to a number, so StringListSizePredicate is a fielded class like NumberPredicate, not split
// further.
public sealed interface StringListPredicate extends Predicate
        permits StringListIncludesPredicate, StringListExcludesPredicate,
        StringListIsEmptyPredicate, StringListIsNotEmptyPredicate, StringListSizePredicate {
}

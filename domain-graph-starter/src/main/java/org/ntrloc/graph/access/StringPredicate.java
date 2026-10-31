package org.ntrloc.graph.access;

// String operators aren't uniformly safe (startsWith/like don't make sense for a number), so unlike
// NumberPredicate/DatePredicate this stays split -- one concrete class per operator.
public sealed interface StringPredicate extends Predicate
        permits StringEqualsPredicate, StringNotEqualsPredicate, StringLikePredicate, StringStartsWithPredicate {
}

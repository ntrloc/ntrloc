package org.ntrloc.graph.access;

// Shared by every "uniform, safely-interchangeable" predicate shape (NumberPredicate, DatePredicate,
// and every list/link predicate reduced to a size/count/sum/avg/min/max). BETWEEN is the only
// operator that needs a second bound -- see the valueTo field on each predicate that uses this enum.
public enum ComparisonOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    LESS_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN_OR_EQUAL,
    BETWEEN
}

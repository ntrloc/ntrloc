package org.ntrloc.graph.access;

// Aggregate-of-values, not a count of matching instances -- a distinct operation from CountedSignal's
// quantification. No "any"/"all" version of a sum exists, which is exactly why this collapses to one
// fielded class instead of needing per-operator splitting.
public record NumberListSumPredicate(ComparisonOperator operator, Number value, Number valueTo) implements NumberListPredicate {
}

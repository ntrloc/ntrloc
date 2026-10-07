package org.ntrloc.graph.access;

// Deliberately separate from ComparisonOperator: dates get BEFORE/AFTER instead of reusing
// GREATER_THAN/LESS_THAN, the same way startsWith exists alongside like -- narrower, more legible
// terms for the domain, even though the comparison underneath is identical. BETWEEN still needs the
// second bound (valueTo) on DatePredicate, same as the numeric case.
public enum DateComparisonOperator {
    EQUALS,
    NOT_EQUALS,
    BEFORE,
    AFTER,
    BETWEEN
}

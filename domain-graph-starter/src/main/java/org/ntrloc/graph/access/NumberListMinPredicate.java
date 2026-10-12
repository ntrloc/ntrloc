package org.ntrloc.graph.access;

public record NumberListMinPredicate(Context subject, ComparisonOperator operator, Number value, Number valueTo) implements NumberListPredicate {
}

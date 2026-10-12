package org.ntrloc.graph.access;

public record NumberListAvgPredicate(Context subject, ComparisonOperator operator, Number value, Number valueTo) implements NumberListPredicate {
}

package org.ntrloc.graph.access;

public record NumberListAvgPredicate(ComparisonOperator operator, Number value, Number valueTo) implements NumberListPredicate {
}

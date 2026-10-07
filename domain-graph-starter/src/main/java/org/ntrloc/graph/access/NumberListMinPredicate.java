package org.ntrloc.graph.access;

public record NumberListMinPredicate(ComparisonOperator operator, Number value, Number valueTo) implements NumberListPredicate {
}

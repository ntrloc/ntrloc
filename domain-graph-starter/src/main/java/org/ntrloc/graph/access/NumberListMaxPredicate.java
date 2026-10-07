package org.ntrloc.graph.access;

public record NumberListMaxPredicate(ComparisonOperator operator, Number value, Number valueTo) implements NumberListPredicate {
}

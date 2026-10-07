package org.ntrloc.graph.access;

public record NumberListSizePredicate(ComparisonOperator operator, int value, int valueTo) implements NumberListPredicate {
}

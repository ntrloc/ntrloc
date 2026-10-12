package org.ntrloc.graph.access;

public record NumberListSizePredicate(Context subject, ComparisonOperator operator, int value, int valueTo) implements NumberListPredicate {
}

package org.ntrloc.graph.access;

public record DateListSizePredicate(Context subject, ComparisonOperator operator, int value, int valueTo) implements DateListPredicate {
}

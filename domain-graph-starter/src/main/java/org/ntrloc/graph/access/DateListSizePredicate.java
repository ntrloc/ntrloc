package org.ntrloc.graph.access;

public record DateListSizePredicate(ComparisonOperator operator, int value, int valueTo) implements DateListPredicate {
}

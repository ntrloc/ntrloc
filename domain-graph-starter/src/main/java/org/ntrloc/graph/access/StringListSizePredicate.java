package org.ntrloc.graph.access;

public record StringListSizePredicate(ComparisonOperator operator, int value, int valueTo) implements StringListPredicate {
}

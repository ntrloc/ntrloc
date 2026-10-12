package org.ntrloc.graph.access;

public record StringListSizePredicate(Context subject, ComparisonOperator operator, int value, int valueTo) implements StringListPredicate {
}

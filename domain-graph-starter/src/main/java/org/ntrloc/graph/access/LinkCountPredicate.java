package org.ntrloc.graph.access;

public record LinkCountPredicate(Context subject, ComparisonOperator operator, int value, int valueTo) implements LinkPredicate {
}

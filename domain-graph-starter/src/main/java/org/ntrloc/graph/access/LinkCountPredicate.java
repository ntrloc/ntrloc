package org.ntrloc.graph.access;

public record LinkCountPredicate(ComparisonOperator operator, int value, int valueTo) implements LinkPredicate {
}

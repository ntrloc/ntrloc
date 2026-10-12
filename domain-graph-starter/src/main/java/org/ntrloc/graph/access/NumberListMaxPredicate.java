package org.ntrloc.graph.access;

public record NumberListMaxPredicate(Context subject, ComparisonOperator operator, Number value, Number valueTo) implements NumberListPredicate {
}

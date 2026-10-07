package org.ntrloc.graph.access;

// Every numeric comparison is safely interchangeable -- no type-mismatch risk the way a string
// operator would have -- so this is one class with an operator field, not six. valueTo is only used
// when operator is BETWEEN; null otherwise.
public record NumberPredicate(ComparisonOperator operator, Number value, Number valueTo) implements Predicate {
}

package org.ntrloc.graph.access;

public sealed interface BooleanPredicate extends Predicate permits IsTruePredicate, IsFalsePredicate {
}

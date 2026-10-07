package org.ntrloc.graph.access;

// A link's own cardinality, read like any other fact -- no special per-instance firing semantics.
public sealed interface LinkPredicate extends Predicate permits LinkIsPresentPredicate, LinkCountPredicate {
}

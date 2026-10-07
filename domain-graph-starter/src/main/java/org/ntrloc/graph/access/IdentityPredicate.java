package org.ntrloc.graph.access;

// Comparisons against an already-reached entity rather than a literal -- EqualsSelfPredicate is the
// only member so far ("creator == self"), the piece that closed the reverse-traversal/"coincidence"
// gap (the Featured Portfolio case). Left sealed-but-extensible in case a future comparison needs to
// reference some other already-reached entity, not just the root.
public sealed interface IdentityPredicate extends Predicate permits EqualsSelfPredicate {
}

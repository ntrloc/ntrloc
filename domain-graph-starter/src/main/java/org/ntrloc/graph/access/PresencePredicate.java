package org.ntrloc.graph.access;

// Whether the subject has a value at all, whatever its type: a property that's set (a list with at
// least one entry), a linked item that's there, a state machine the item is in.
public sealed interface PresencePredicate extends Predicate permits ExistsPredicate, NotExistsPredicate {
}

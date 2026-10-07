package org.ntrloc.graph.access;

// The pre-entry case -- the item hasn't been placed on this state machine yet. Same shape as an
// emptiness check on a collection, not a special case.
public record NoStatePredicate() implements StateMachinePredicate {
}

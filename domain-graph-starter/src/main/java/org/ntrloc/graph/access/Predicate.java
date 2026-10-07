package org.ntrloc.graph.access;

// Split per base type only where the operator set carries real type-safety risk (string, list
// membership/presence, boolean, state machine, identity); collapsed to one fielded class wherever
// every operator is safely interchangeable (number, date, and any list/link reduced to a size, count,
// sum, avg, min, or max -- all of which are themselves just numbers or dates). See the marker
// decision pipeline design notes, "Signals: reading facts" / "Object shape", for the full reasoning.
//
// A predicate never has an output pin of its own except as a member of a PredicateSignal -- see Pin.
public sealed interface Predicate
        permits StringPredicate, NumberPredicate, DatePredicate, BooleanPredicate,
        StringListPredicate, NumberListPredicate, DateListPredicate,
        LinkPredicate, StateMachinePredicate, IdentityPredicate {
}

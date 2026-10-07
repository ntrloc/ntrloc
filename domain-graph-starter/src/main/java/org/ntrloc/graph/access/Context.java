package org.ntrloc.graph.access;

// A context is where a signal reads relative to: empty (Self) is the item the pipeline belongs to,
// and every other variant is one step away from wherever it's nested, wrapping its own continuation.
// StepToStateMachine has no `next` -- it's structurally terminal, so "a state machine leads somewhere
// else" is impossible to construct rather than a rule enforced elsewhere. `each` on the three
// steppable variants marks whether a to-many step is iterated (correlated per instance) or flattened;
// see the marker decision pipeline design notes, "Signals: reading facts", for the full reasoning.
public sealed interface Context
        permits Self, StepToLink, StepToItem, StepToProperty, StepToStateMachine {
}

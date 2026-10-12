package org.ntrloc.graph.access;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// A context is where a signal reads relative to: empty (Self) is the item the pipeline belongs to,
// and every other variant is one step away from wherever it's nested, wrapping its own continuation.
// StepToStateMachine has no `next` -- it's structurally terminal, so "a state machine leads somewhere
// else" is impossible to construct rather than a rule enforced elsewhere. `each` on the three
// steppable variants marks whether a to-many step is iterated (correlated per instance) or flattened;
// see the marker decision pipeline design notes, "Conditions: reading facts", for the full reasoning.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Self.class, name = "SELF"),
        @JsonSubTypes.Type(value = StepToLink.class, name = "STEP_TO_LINK"),
        @JsonSubTypes.Type(value = StepToItem.class, name = "STEP_TO_ITEM"),
        @JsonSubTypes.Type(value = StepToProperty.class, name = "STEP_TO_PROPERTY"),
        @JsonSubTypes.Type(value = StepToStateMachine.class, name = "STEP_TO_STATE_MACHINE")
})
public sealed interface Context
        permits Self, StepToLink, StepToItem, StepToProperty, StepToStateMachine {
}

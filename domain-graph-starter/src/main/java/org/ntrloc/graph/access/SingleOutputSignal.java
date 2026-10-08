package org.ntrloc.graph.access;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

// Anything that reduces to exactly one boolean, usable wherever "a signal" is expected as a
// sub-signal (CompoundSignal.subSignals, CountedSignal.subSignal) or as a carrier block's gate.
// PredicateSignal is deliberately excluded -- it's multi-pin (each predicate independently wireable)
// and can't stand in for a single boolean, so it's only ever valid as a top-level block.
//
// id() is this block's one output pin's identity -- what a carrier block's `gate` field (a UUID)
// resolves against. See PinnedPredicate for the other place a pin identity lives (one per predicate
// inside a PredicateSignal, since that block has no pin of its own).
//
// Own @JsonTypeInfo/@JsonSubTypes, duplicating SignalBlock's own list minus PredicateSignal --
// needed wherever a field is declared as this narrower type (CompoundSignal.subSignals,
// CountedSignal.subSignal), since Jackson resolves polymorphism from the field's own declared type.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Signal.class, name = "SIGNAL"),
        @JsonSubTypes.Type(value = CompoundSignal.class, name = "COMPOUND_SIGNAL"),
        @JsonSubTypes.Type(value = CountedSignal.class, name = "COUNTED_SIGNAL")
})
public sealed interface SingleOutputSignal extends SignalBlock permits Signal, CompoundSignal, CountedSignal {

    UUID id();
}

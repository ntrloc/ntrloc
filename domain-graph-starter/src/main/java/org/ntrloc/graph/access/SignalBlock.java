package org.ntrloc.graph.access;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// Any of the four signal-block kinds. Pull-evaluated, never pushed into -- a signal block has no
// input pin of any kind, not even a gate; it's a pure, self-contained computation over its own
// Context/Predicate(s), evaluated only because something downstream needs its value. See "Signals:
// reading facts" / "Pull, not push" in the marker decision pipeline design notes.
//
// @JsonSubTypes lists the three SingleOutputSignal leaves directly alongside PredicateSignal,
// skipping that intermediate sealed interface -- see SingleOutputSignal's own annotation for why it
// still needs the identical list on its own, for the places a field is declared as that narrower type.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Signal.class, name = "SIGNAL"),
        @JsonSubTypes.Type(value = CompoundSignal.class, name = "COMPOUND_SIGNAL"),
        @JsonSubTypes.Type(value = CountedSignal.class, name = "COUNTED_SIGNAL"),
        @JsonSubTypes.Type(value = PredicateSignal.class, name = "PREDICATE_SIGNAL")
})
public sealed interface SignalBlock permits SingleOutputSignal, PredicateSignal {
}

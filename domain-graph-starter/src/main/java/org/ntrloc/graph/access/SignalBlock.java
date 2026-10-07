package org.ntrloc.graph.access;

// Any of the four signal-block kinds. Pull-evaluated, never pushed into -- a signal block has no
// input pin of any kind, not even a gate; it's a pure, self-contained computation over its own
// Context/Predicate(s), evaluated only because something downstream needs its value. See "Signals:
// reading facts" / "Pull, not push" in the marker decision pipeline design notes.
public sealed interface SignalBlock permits SingleOutputSignal, PredicateSignal {
}

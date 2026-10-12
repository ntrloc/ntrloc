package org.ntrloc.graph.access;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// Either of the two signal-block kinds. Pull-evaluated, never pushed into -- a signal block has no
// input pin of any kind, not even a gate; it's a pure, self-contained computation over its own
// Context/Predicate(s), evaluated only because something downstream needs its value. See "Conditions:
// reading facts" / "Pull, not push" in the marker decision pipeline design notes.
//
// Replaces the old four-kind split (Signal/CompoundSignal/CountedSignal/PredicateSignal) now that
// any/all/none and "counted" are predicate kinds, not separate wrapping blocks -- see Predicate's own
// comment. A Condition always has exactly one output pin; a ConditionGroup has none of its own (each
// contained Condition has its own).
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Condition.class, name = "CONDITION"),
        @JsonSubTypes.Type(value = ConditionGroup.class, name = "CONDITION_GROUP")
})
public sealed interface SignalBlock permits Condition, ConditionGroup {
}

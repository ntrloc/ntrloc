package org.ntrloc.graph.access;

import java.util.UUID;

// Replaces Signal. A condition is the pipeline's only mechanism for turning a fact, or combination of
// facts, into a single boolean a decision step can act on -- see the marker decision pipeline design
// notes, "Conditions: reading facts". Always wraps exactly one predicate; its own output is a pure
// passthrough of that predicate's result, never a separate computation.
//
// `subject` is nullable -- DRY-only; inherited from whatever contains this condition when absent. A
// top-level condition with no subject of its own resolves against Self (the item the pipeline belongs
// to), the same root default signals always had.
//
// `id` is this condition's own output pin's identity -- what a carrier block's `gate` field (a UUID)
// resolves against, whether this condition sits standalone at the pipeline's top level or nested inside
// a ConditionGroup.
public record Condition(UUID id, Context subject, Predicate predicate) implements SignalBlock {

    public Condition {
        if (id == null) throw new IllegalArgumentException("Condition requires an id");
        if (predicate == null) throw new IllegalArgumentException("Condition requires exactly one predicate");
    }
}

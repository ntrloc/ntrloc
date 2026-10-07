package org.ntrloc.graph.access;

import java.util.UUID;

// The base case: exactly one predicate, no sub-signals. `subject` is a DRY-only declaration (nameable
// once when it would otherwise be repeated across several predicates) -- a lone predicate can just
// name its subject inline and leave this null. `id` is this block's one output pin's identity.
public record Signal(UUID id, Context context, String subject, Predicate predicate) implements SingleOutputSignal {

    public Signal {
        if (id == null) throw new IllegalArgumentException("Signal requires an id");
        if (context == null) throw new IllegalArgumentException("Signal requires a context");
        if (predicate == null) throw new IllegalArgumentException("Signal requires exactly one predicate");
    }
}

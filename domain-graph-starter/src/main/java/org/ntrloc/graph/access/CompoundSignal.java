package org.ntrloc.graph.access;

import java.util.List;
import java.util.UUID;

// A fixed-arity ANY/ALL across several distinct, named sub-signals, each evaluated exactly once
// (never repeated -- that's CountedSignal's job). Combines different kinds of facts about one
// instance, e.g. a flat predicate on a link's own property combined with a nested sub-signal about
// the item on the other end of that same link. subSignals is ordered deliberately: evaluation
// proceeds left to right and short-circuits as soon as the ANY/ALL result is determined, the same way
// ordinary boolean short-circuiting works anywhere else -- this is what makes an expensive nested
// signal skippable without needing a gate field of its own. Minimum 2, same reasoning as
// PredicateSignal: one sub-signal wouldn't be "combining" anything. `id` is this block's one output
// pin's identity.
public record CompoundSignal(UUID id, Combinator combinator, List<SingleOutputSignal> subSignals) implements SingleOutputSignal {

    public CompoundSignal {
        if (id == null) throw new IllegalArgumentException("CompoundSignal requires an id");
        if (combinator == null) throw new IllegalArgumentException("CompoundSignal requires a combinator");
        if (subSignals == null || subSignals.size() < 2) {
            throw new IllegalArgumentException("CompoundSignal requires at least 2 sub-signals");
        }
        subSignals = List.copyOf(subSignals);
    }
}

package org.ntrloc.graph.access;

import java.util.UUID;

// Wraps exactly one sub-signal, evaluated repeatedly (once per instance of a to-many relationship via
// an `each` step somewhere in context), and compares how many of those evaluations fired against how
// many ran via an ordinary predicate (countPredicate: "signal count > N", "all signals on", etc.).
// This is how any/all/arbitrary-threshold quantification over a to-many link is expressed -- not as
// separate combinator types, just different thresholds on the same count. The wrapped subSignal can
// itself be a CompoundSignal or another CountedSignal, nested arbitrarily deep. `id` is this block's
// one output pin's identity.
public record CountedSignal(UUID id, Context context, SingleOutputSignal subSignal, Predicate countPredicate) implements SingleOutputSignal {

    public CountedSignal {
        if (id == null) throw new IllegalArgumentException("CountedSignal requires an id");
        if (context == null) throw new IllegalArgumentException("CountedSignal requires a context");
        if (subSignal == null) throw new IllegalArgumentException("CountedSignal requires exactly one sub-signal");
        if (countPredicate == null) throw new IllegalArgumentException("CountedSignal requires a count predicate");
        if (!containsEachStep(context)) {
            throw new IllegalArgumentException("CountedSignal's context must include an `each` step somewhere in the chain");
        }
    }

    private static boolean containsEachStep(Context context) {
        if (context instanceof StepToLink step) return step.each() || containsEachStep(step.next());
        if (context instanceof StepToItem step) return step.each() || containsEachStep(step.next());
        if (context instanceof StepToProperty step) return step.each() || containsEachStep(step.next());
        return false; // Self, StepToStateMachine -- neither is steppable
    }
}

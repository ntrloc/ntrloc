package org.ntrloc.graph.access;

// Replaces CountedSignal -- wraps exactly one sub-predicate, evaluated repeatedly (once per instance
// of a to-many relationship via an `each` step somewhere in its own subject), and compares how many of
// those evaluations fired against how many ran, via an ordinary predicate (`count > 0`, `all matched`,
// etc.). This is how any/all/arbitrary-threshold quantification over a to-many link is expressed -- not
// as separate quantity kinds, just different thresholds on the same count. The wrapped sub-predicate
// can itself be a TotalityPredicate or another CountedPredicate, nested arbitrarily deep.
//
// Unlike every other predicate, `subject` here is required, not optional -- there's nothing sensible
// to inherit "repeat this per instance of ___" from if nothing names the to-many relationship at this
// level.
public record CountedPredicate(Context subject, Predicate subPredicate, Predicate countPredicate) implements Predicate {

    public CountedPredicate {
        if (subject == null) throw new IllegalArgumentException("CountedPredicate requires a subject");
        if (subPredicate == null) throw new IllegalArgumentException("CountedPredicate requires exactly one sub-predicate");
        if (countPredicate == null) throw new IllegalArgumentException("CountedPredicate requires a count predicate");
        if (!containsEachStep(subject)) {
            throw new IllegalArgumentException("CountedPredicate's subject must include an `each` step somewhere in the chain");
        }
    }

    private static boolean containsEachStep(Context context) {
        return switch (context) {
            case StepToLink step -> step.each() || containsEachStep(step.next());
            case StepToItem step -> step.each() || containsEachStep(step.next());
            case StepToProperty step -> step.each() || containsEachStep(step.next());
            case Self _, StepToStateMachine _ -> false; // neither is steppable
            case null -> false;
        };
    }
}

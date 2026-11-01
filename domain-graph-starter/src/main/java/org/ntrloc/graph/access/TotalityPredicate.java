package org.ntrloc.graph.access;

import java.util.List;

// Replaces CompoundSignal -- any/all/none across 1..n sub-predicates, each itself any predicate kind
// (value, totality, or counted -- nests arbitrarily deep). Named for reflecting how *fully* the
// totality of its sub-predicates was satisfied (ALL = completely, ANY = at least partially, NONE = not
// at all) -- deliberately not "boolean predicate," which would collide with the existing
// BooleanPredicate (IsTruePredicate/IsFalsePredicate, a value predicate testing a boolean-typed
// subject -- an unrelated concept that happens to want the same word).
//
// subPredicates is ordered deliberately: evaluation proceeds left to right and short-circuits as soon
// as the quantity's result is determined, the same way ordinary boolean short-circuiting works
// anywhere else -- see SignalEvaluator. Minimum 1, as for ConditionGroup: one sub-predicate combines
// nothing, but it's where a group starts in the editor, before more are added.
public record TotalityPredicate(Context subject, Quantity quantity, List<Predicate> subPredicates) implements Predicate {

    public TotalityPredicate {
        if (quantity == null) throw new IllegalArgumentException("TotalityPredicate requires a quantity");
        if (subPredicates == null || subPredicates.isEmpty()) {
            throw new IllegalArgumentException("TotalityPredicate requires at least 1 sub-predicate");
        }
        subPredicates = List.copyOf(subPredicates);
    }
}

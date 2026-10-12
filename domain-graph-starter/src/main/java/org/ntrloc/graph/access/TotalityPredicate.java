package org.ntrloc.graph.access;

import java.util.List;

// Replaces CompoundSignal -- any/all/none across 2..n sub-predicates, each itself any predicate kind
// (value, totality, or counted -- nests arbitrarily deep). Named for reflecting how *fully* the
// totality of its sub-predicates was satisfied (ALL = completely, ANY = at least partially, NONE = not
// at all) -- deliberately not "boolean predicate," which would collide with the existing
// BooleanPredicate (IsTruePredicate/IsFalsePredicate, a value predicate testing a boolean-typed
// subject -- an unrelated concept that happens to want the same word).
//
// subPredicates is ordered deliberately: evaluation proceeds left to right and short-circuits as soon
// as the quantity's result is determined, the same way ordinary boolean short-circuiting works
// anywhere else -- see SignalEvaluator. Minimum 2, same reasoning as ConditionGroup: one sub-predicate
// wouldn't be "combining" anything, it'd just be that predicate directly.
public record TotalityPredicate(Context subject, Quantity quantity, List<Predicate> subPredicates) implements Predicate {

    public TotalityPredicate {
        if (quantity == null) throw new IllegalArgumentException("TotalityPredicate requires a quantity");
        if (subPredicates == null || subPredicates.size() < 2) {
            throw new IllegalArgumentException("TotalityPredicate requires at least 2 sub-predicates");
        }
        subPredicates = List.copyOf(subPredicates);
    }
}

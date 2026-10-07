package org.ntrloc.graph.access;

import java.util.List;

// Groups several predicates that share a declared (or implicit) subject but are independently
// consequential -- each predicate keeps its own pin (PinnedPredicate), wired separately. The shape
// for "one property, several distinct outcomes" -- a single-valued property picking between several
// different markers, or several independent conditions on a multi-valued property. Minimum 2: a
// single predicate wouldn't need this at all, it'd just be a Signal. No id of its own -- the block
// itself is never a gate target, only its individual predicates are.
public record PredicateSignal(Context context, String subject, List<PinnedPredicate> predicates) implements SignalBlock {

    public PredicateSignal {
        if (context == null) throw new IllegalArgumentException("PredicateSignal requires a context");
        if (predicates == null || predicates.size() < 2) {
            throw new IllegalArgumentException("PredicateSignal requires at least 2 predicates");
        }
        predicates = List.copyOf(predicates);
    }
}

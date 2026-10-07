package org.ntrloc.graph.access;

import java.util.UUID;

// A predicate paired with its own independent pin identity -- the shape every predicate takes inside
// a PredicateSignal, since that block has no output pin of its own and each predicate is
// independently wireable instead. A bare Signal's single predicate needs no id of its own; the
// Signal's own id already addresses it uniquely.
public record PinnedPredicate(UUID id, Predicate predicate) {

    public PinnedPredicate {
        if (id == null) throw new IllegalArgumentException("PinnedPredicate requires an id");
        if (predicate == null) throw new IllegalArgumentException("PinnedPredicate requires a predicate");
    }
}

package org.ntrloc.graph.access;

import java.util.List;

// Replaces PredicateSignal. Groups 1..n conditions that share a declared (or implicit) subject but are
// independently consequential -- each condition keeps its own output pin, since each is a full
// Condition in its own right. No row-wrapper type needed (the old PinnedPredicate is gone): a Condition
// already carries everything a grouped entry needs -- its own output, its own optional subject, its own
// predicate.
//
// No id of its own, and no output pin -- nothing to expose, since every condition inside it already has
// its own. ConditionGroup's entire value is authoring convenience, not expressive power: everything it
// can do is already reachable with N independent top-level Conditions; it just saves repeating a shared
// subject across them (e.g. "firstName is not null" and "length > 10" both read against one declared
// "property: firstName"), or simply keeps related conditions together visually. <switch> (a condition
// group with exactly two conditions, IsTruePredicate and IsFalsePredicate, sharing one subject) is a
// highly specific instance of this shape, not a concept of its own.
public record ConditionGroup(Context subject, List<Condition> conditions) implements SignalBlock {

    public ConditionGroup {
        // One is a group still being built: the editor starts every group with one and adds more.
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("ConditionGroup requires at least 1 condition");
        }
        conditions = List.copyOf(conditions);
    }
}

package org.ntrloc.graph.access;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Derives a pipeline's watch set directly from its signal graph -- not separately authored. Revises
// the original design (explicit, admin-declared watches, never derived) now that a pipeline's signals
// are a fully typed Context graph the admin already built deliberately, block by block: deriving
// watches from that graph is just reading back out exactly what was already, consciously authored,
// not inferring from looser logic the way the original "never derived" decision was guarding against.
//
// The atomic unit is each individual "thing" referenced anywhere in any condition's subject chain -- a
// specific property, link, or state machine, by id -- not the chain as a whole: a change to any one of
// them is independently a reason to re-run the pipeline. `each` doesn't matter for identity: it's
// about how a step gets consumed within one particular chain, not what's being referenced. See
// "Pipeline-level watches" in the marker decision pipeline design notes.
public final class WatchDerivation {

    // Reserved sentinel, not a real property/link/state-machine id -- stands for "this item was
    // just created." Added only when a pipeline has no signal blocks at all (see derive() below): a
    // pipeline built from nothing but unconditional marker actions (e.g. a bare <set> with no gate)
    // can never be triggered by a fact change, since it doesn't reference any fact, but it still
    // needs exactly one trigger to ever run -- item creation. A pipeline that has a real condition is
    // never touched by this, even one whose own watch set happens to come out empty (e.g. a bare
    // Self subject with no steps) -- that pipeline was deliberately gated on something, just not on
    // anything WatchDerivation can currently identify, which is a different situation from having no
    // gate at all and must not be papered over by this sentinel.
    public static final UUID ITEM_CREATED = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private WatchDerivation() {
    }

    public static Set<UUID> derive(Pipeline pipeline) {
        List<SignalBlock> blocks = pipeline.topLevelSignals();
        if (blocks.isEmpty()) {
            return Set.of(ITEM_CREATED);
        }
        Set<UUID> watchedIds = new HashSet<>();
        for (SignalBlock block : blocks) {
            switch (block) {
                case Condition c -> collectConditionWatches(c, null, watchedIds);
                // Each member condition inherits the group's own subject when it has none of its
                // own -- the same relationship SignalEvaluator resolves at evaluation time; watch
                // derivation has to follow the identical inheritance chain or it under-counts exactly
                // the facts a condition actually reads.
                case ConditionGroup g -> g.conditions().forEach(c -> collectConditionWatches(c, g.subject(), watchedIds));
            }
        }
        return watchedIds;
    }

    private static void collectConditionWatches(Condition condition, Context inherited, Set<UUID> ids) {
        Context subject = condition.subject() != null ? condition.subject() : inherited;
        collectPredicateWatches(condition.predicate(), subject, ids);
    }

    // Walks a predicate's own tree collecting every *effective* subject at any depth -- a leaf value
    // predicate's own subject, or (when it has none) whatever it would inherit from its parent; a
    // TotalityPredicate/CountedPredicate's own effective subject, plus everything nested inside it,
    // each resolving the same way. Self (or an entirely absent chain) contributes nothing, same as
    // collectReferencedIds below.
    private static void collectPredicateWatches(Predicate predicate, Context inherited, Set<UUID> ids) {
        Context subject = predicate.subject() != null ? predicate.subject() : inherited;
        if (subject != null) {
            collectReferencedIds(subject, ids);
        }
        switch (predicate) {
            case TotalityPredicate t -> t.subPredicates().forEach(sub -> collectPredicateWatches(sub, subject, ids));
            case CountedPredicate c -> {
                collectPredicateWatches(c.subPredicate(), subject, ids);
                collectPredicateWatches(c.countPredicate(), subject, ids);
            }
            default -> {
                // A leaf value predicate nests nothing further.
            }
        }
    }

    private static void collectReferencedIds(Context context, Set<UUID> ids) {
        switch (context) {
            case StepToLink step -> {
                ids.add(step.linkPerspectiveId());
                collectReferencedIds(step.next(), ids);
            }
            case StepToItem step -> {
                ids.add(step.linkPerspectiveId());
                collectReferencedIds(step.next(), ids);
            }
            case StepToProperty step -> {
                ids.add(step.propertyId());
                collectReferencedIds(step.next(), ids);
            }
            case StepToStateMachine step -> ids.add(step.stateMachineId());
            // Self, or a chain with no terminal step: nothing to add, nothing to recurse into.
            case Self _ -> { /* nothing */ }
            case null -> { /* nothing */ }
        }
    }
}

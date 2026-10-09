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
// The atomic unit is each individual "thing" referenced anywhere in any signal's context chain -- a
// specific property, link, or state machine, by id -- not the chain as a whole: a change to any one of
// them is independently a reason to re-run the pipeline. `each` doesn't matter for identity: it's
// about how a step gets consumed within one particular chain, not what's being referenced. See
// "Pipeline-level watches" in the marker decision pipeline design notes.
public final class WatchDerivation {

    // Reserved sentinel, not a real property/link/state-machine id -- stands for "this item was
    // just created." Added only when a pipeline has no signal blocks at all (see derive() below): a
    // pipeline built from nothing but unconditional marker actions (e.g. a bare <set> with no gate)
    // can never be triggered by a fact change, since it doesn't reference any fact, but it still
    // needs exactly one trigger to ever run -- item creation. A pipeline that has a real signal is
    // never touched by this, even one whose own watch set happens to come out empty (e.g. a bare
    // Self context with no steps) -- that pipeline was deliberately gated on something, just not on
    // anything WatchDerivation can currently identify, which is a different situation from having no
    // gate at all and must not be papered over by this sentinel.
    public static final UUID ITEM_CREATED = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private WatchDerivation() {
    }

    public static Set<UUID> derive(Pipeline pipeline) {
        List<SignalBlock> signalBlocks = pipeline.allSignalBlocks();
        if (signalBlocks.isEmpty()) {
            return Set.of(ITEM_CREATED);
        }
        Set<UUID> watchedIds = new HashSet<>();
        for (SignalBlock block : signalBlocks) {
            Context context = contextOf(block);
            if (context != null) {
                collectReferencedIds(context, watchedIds);
            }
        }
        return watchedIds;
    }

    // CompoundSignal has no context of its own -- it only combines sub-signals, each of which is
    // separately visited by Pipeline.allSignalBlocks()'s own recursion.
    private static Context contextOf(SignalBlock block) {
        if (block instanceof Signal s) return s.context();
        if (block instanceof PredicateSignal s) return s.context();
        if (block instanceof CountedSignal s) return s.context();
        return null;
    }

    private static void collectReferencedIds(Context context, Set<UUID> ids) {
        if (context instanceof StepToLink step) {
            ids.add(step.linkPerspectiveId());
            collectReferencedIds(step.next(), ids);
        } else if (context instanceof StepToItem step) {
            ids.add(step.linkPerspectiveId());
            collectReferencedIds(step.next(), ids);
        } else if (context instanceof StepToProperty step) {
            ids.add(step.propertyId());
            collectReferencedIds(step.next(), ids);
        } else if (context instanceof StepToStateMachine step) {
            ids.add(step.stateMachineId());
        }
        // Self -- nothing to add, nothing further to recurse into.
    }
}

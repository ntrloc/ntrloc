package org.ntrloc.graph.access;

import java.util.HashSet;
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

    private WatchDerivation() {
    }

    public static Set<UUID> derive(Pipeline pipeline) {
        Set<UUID> watchedIds = new HashSet<>();
        for (SignalBlock block : pipeline.allSignalBlocks()) {
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

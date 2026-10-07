package org.ntrloc.graph.access;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// One marker decision pipeline. Start/End are dedicated fields, not entries in carrierBlocks -- that
// makes "exactly one start, exactly one end" true by construction rather than a count someone has to
// check. topLevelSignals holds only the signal blocks a carrier block's gate can actually reach
// (nested sub-signals inside a CompoundSignal/CountedSignal are reached transitively, not listed
// separately here -- they're embedded in their parent's own tree). No watches field: watches are
// derived from the signal graph, not separately authored -- see WatchDerivation.
//
// Validates eagerly, at construction, that every gate/signal reference and every Route id actually
// resolves against this pipeline's own graph. Deeper structural checks (every carrier block reachable
// from start, every path reaching end) are a separate concern -- see PipelineValidation -- since they
// need real graph traversal, not just a lookup.
public record Pipeline(
        UUID id,
        List<SignalBlock> topLevelSignals,
        List<CarrierBlock> carrierBlocks,
        Start start,
        End end,
        List<Route> routes
) {

    public Pipeline {
        if (id == null) throw new IllegalArgumentException("Pipeline requires an id");
        if (start == null) throw new IllegalArgumentException("Pipeline requires exactly one start");
        if (end == null) throw new IllegalArgumentException("Pipeline requires exactly one end");
        topLevelSignals = topLevelSignals == null ? List.of() : List.copyOf(topLevelSignals);
        carrierBlocks = carrierBlocks == null ? List.of() : List.copyOf(carrierBlocks);
        routes = routes == null ? List.of() : List.copyOf(routes);

        Set<UUID> signalPinIds = collectSignalPinIds(topLevelSignals);
        for (CarrierBlock block : carrierBlocks) {
            UUID gate = gateOf(block);
            if (gate != null && !signalPinIds.contains(gate)) {
                throw new IllegalArgumentException(
                        "Carrier block " + block.id() + " references unknown signal pin " + gate);
            }
        }

        Set<UUID> carrierBlockIds = new HashSet<>();
        carrierBlockIds.add(start.id());
        carrierBlockIds.add(end.id());
        for (CarrierBlock block : carrierBlocks) {
            carrierBlockIds.add(block.id());
        }
        for (Route route : routes) {
            if (!carrierBlockIds.contains(route.outputPinId())) {
                throw new IllegalArgumentException(
                        "Route references unknown carrier block " + route.outputPinId());
            }
            for (UUID target : route.inputPinIds()) {
                if (!carrierBlockIds.contains(target)) {
                    throw new IllegalArgumentException(
                            "Route references unknown carrier block " + target);
                }
            }
        }
    }

    // null for Start/End/AddMarkers|RemoveMarkers|SetMarkers-with-no-gate; Filter's signal is
    // mandatory so it's never null there.
    private static UUID gateOf(CarrierBlock block) {
        if (block instanceof AddMarkers b) return b.gate();
        if (block instanceof RemoveMarkers b) return b.gate();
        if (block instanceof SetMarkers b) return b.gate();
        if (block instanceof Filter b) return b.signal();
        return null; // Start, End
    }

    // Every addressable pin id reachable from this pipeline's top-level signals: a SingleOutputSignal's
    // own id, recursing into CompoundSignal.subSignals / CountedSignal.subSignal, plus each
    // PinnedPredicate's id inside a PredicateSignal.
    static Set<UUID> collectSignalPinIds(List<SignalBlock> topLevelSignals) {
        Set<UUID> ids = new HashSet<>();
        for (SignalBlock block : topLevelSignals) {
            collectSignalPinIds(block, ids);
        }
        return ids;
    }

    private static void collectSignalPinIds(SignalBlock block, Set<UUID> ids) {
        if (block instanceof Signal s) {
            ids.add(s.id());
        } else if (block instanceof CompoundSignal s) {
            ids.add(s.id());
            for (SingleOutputSignal sub : s.subSignals()) {
                collectSignalPinIds(sub, ids);
            }
        } else if (block instanceof CountedSignal s) {
            ids.add(s.id());
            collectSignalPinIds(s.subSignal(), ids);
        } else if (block instanceof PredicateSignal s) {
            for (PinnedPredicate p : s.predicates()) {
                ids.add(p.id());
            }
        }
    }

    // All top-level and nested signal blocks in this pipeline, depth-first -- used by watch
    // derivation to walk every signal's context.
    List<SignalBlock> allSignalBlocks() {
        List<SignalBlock> all = new ArrayList<>();
        for (SignalBlock block : topLevelSignals) {
            collectSignalBlocks(block, all);
        }
        return all;
    }

    private static void collectSignalBlocks(SignalBlock block, List<SignalBlock> all) {
        all.add(block);
        if (block instanceof CompoundSignal s) {
            for (SingleOutputSignal sub : s.subSignals()) {
                collectSignalBlocks(sub, all);
            }
        } else if (block instanceof CountedSignal s) {
            collectSignalBlocks(s.subSignal(), all);
        }
    }
}

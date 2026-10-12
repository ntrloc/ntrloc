package org.ntrloc.graph.access;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// One marker decision pipeline. Start/End are dedicated fields, not entries in carrierBlocks -- that
// makes "exactly one start, exactly one end" true by construction rather than a count someone has to
// check. topLevelSignals holds every Condition/ConditionGroup a carrier block's Gate can actually
// reach -- a ConditionGroup's own conditions are listed inside it, not separately here, but each still
// has its own addressable id (see Condition's own comment). No watches field: watches are derived from
// the signal graph, not separately authored -- see WatchDerivation.
//
// Validates eagerly, at construction, that every Gate's condition pins and every Route id actually
// resolve against this pipeline's own graph. Deeper structural checks (every carrier block reachable
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
            for (Gate gate : gatesOf(block)) {
                for (UUID pin : gate.conditionOutputPinIds()) {
                    if (!signalPinIds.contains(pin)) {
                        throw new IllegalArgumentException(
                                "Carrier block " + block.id() + " references unknown signal pin " + pin);
                    }
                }
            }
        }

        Set<UUID> carrierBlockIds = new HashSet<>();
        carrierBlockIds.add(start.id());
        carrierBlockIds.add(end.id());
        for (CarrierBlock block : carrierBlocks) {
            carrierBlockIds.add(block.id());
            if (block instanceof Select s) {
                for (AddMarkers row : s.adds()) {
                    carrierBlockIds.add(row.id());
                }
            }
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

    // Every Gate this block owns -- its own, plus (for Select) each row's own, since a row's gate is
    // just as real a point of contact with the signal domain as any standalone AddMarkers' is. Empty
    // for Start/End, which have no Gate at all.
    private static List<Gate> gatesOf(CarrierBlock block) {
        if (block instanceof AddMarkers b) return List.of(b.gate());
        if (block instanceof RemoveMarkers b) return List.of(b.gate());
        if (block instanceof SetMarkers b) return List.of(b.gate());
        if (block instanceof Filter b) return List.of(b.signal());
        if (block instanceof Select s) {
            List<Gate> gates = new ArrayList<>();
            gates.add(s.gate());
            for (AddMarkers row : s.adds()) {
                gates.add(row.gate());
            }
            return gates;
        }
        return List.of(); // Start, End
    }

    // Every addressable pin id reachable from this pipeline's top-level signals: a standalone
    // Condition's own id, or each Condition's id inside a ConditionGroup. A predicate (even a
    // TotalityPredicate/CountedPredicate nesting others) never has a pin of its own -- see Predicate's
    // own comment -- so no deeper recursion is needed here.
    static Set<UUID> collectSignalPinIds(List<SignalBlock> topLevelSignals) {
        Set<UUID> ids = new HashSet<>();
        for (SignalBlock block : topLevelSignals) {
            if (block instanceof Condition c) {
                ids.add(c.id());
            } else if (block instanceof ConditionGroup g) {
                for (Condition c : g.conditions()) {
                    ids.add(c.id());
                }
            }
        }
        return ids;
    }
}

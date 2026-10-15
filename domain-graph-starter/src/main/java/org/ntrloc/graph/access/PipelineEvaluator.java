package org.ntrloc.graph.access;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Walks a pipeline's carrier graph from Start to End, resolving each carrier block's Gate (if any) via
// SignalEvaluator and applying its own transform to the carrier flowing through it. A block fed by
// more than one Route merges every incoming carrier by union before applying its own transform
// (Route's own comment: "fan-in... is just implicit carrier merge"); End is the union of every
// carrier that ever arrives there -- the pipeline's final marker decision for this item.
public final class PipelineEvaluator {

    private PipelineEvaluator() {
    }

    // Legacy overload -- every existing caller (and its tests) keeps exactly the behavior it always
    // had: a pipeline that actually needs multi-instance traversal still throws
    // UnsupportedOperationException, since there's no resolver here to ask.
    public static Set<UUID> evaluate(Pipeline pipeline, Map<UUID, Object> selfProperties, UUID selfId) {
        return evaluate(pipeline, selfProperties, selfId, FactResolver.unsupported());
    }

    public static Set<UUID> evaluate(Pipeline pipeline, Map<UUID, Object> selfProperties, UUID selfId, FactResolver facts) {
        Map<UUID, CarrierBlock> blocksById = new HashMap<>();
        blocksById.put(pipeline.start().id(), pipeline.start());
        blocksById.put(pipeline.end().id(), pipeline.end());
        // A Select's own rows aren't top-level carrier blocks, but each row's AddMarkers still needs
        // to be independently addressable as a carrier pin (something else can route to NATURE's own
        // output, say) -- registered here alongside a reverse lookup to the Select that owns it, since
        // a row's carrierIn is "whatever fed the Select itself", not something routed to the row
        // directly -- nothing ever routes to a row from outside (see Select's own comment).
        Map<UUID, Select> selectOwningRow = new HashMap<>();
        for (CarrierBlock block : pipeline.carrierBlocks()) {
            blocksById.put(block.id(), block);
            if (block instanceof Select s) {
                for (AddMarkers row : s.adds()) {
                    blocksById.put(row.id(), row);
                    selectOwningRow.put(row.id(), s);
                }
            }
        }

        Map<UUID, List<UUID>> incomingByTarget = new HashMap<>();
        for (Route route : pipeline.routes()) {
            for (UUID target : route.inputPinIds()) {
                incomingByTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(route.outputPinId());
            }
        }

        return carrierOut(pipeline.end().id(), blocksById, incomingByTarget, selectOwningRow, pipeline.topLevelSignals(),
                selfProperties, selfId, facts, new HashMap<>(), new HashSet<>());
    }

    private static Set<UUID> carrierOut(UUID blockId, Map<UUID, CarrierBlock> blocksById, Map<UUID, List<UUID>> incomingByTarget,
                                         Map<UUID, Select> selectOwningRow, List<SignalBlock> topLevelSignals,
                                         Map<UUID, Object> selfProperties, UUID selfId, FactResolver facts,
                                         Map<UUID, Set<UUID>> cache, Set<UUID> inProgress) {
        Set<UUID> cached = cache.get(blockId);
        if (cached != null) return cached;
        if (!inProgress.add(blockId)) {
            throw new IllegalStateException("Cycle detected in pipeline carrier graph at block " + blockId);
        }

        Set<UUID> result;
        Select owner = selectOwningRow.get(blockId);
        if (owner != null) {
            // blockId is one of owner's own row ids -- its carrierIn is whatever fed the Select
            // itself, resolved via the owner's own id, never via blockId's own (nonexistent) Route
            // entries.
            Set<UUID> carrierIn = gatherIncoming(owner.id(), blocksById, incomingByTarget, selectOwningRow,
                    topLevelSignals, selfProperties, selfId, facts, cache, inProgress);
            result = applyBlock(blocksById.get(blockId), carrierIn, topLevelSignals, selfProperties, selfId, facts);
        } else {
            CarrierBlock block = blocksById.get(blockId);
            if (block instanceof Start) {
                result = Set.of();
            } else {
                Set<UUID> carrierIn = gatherIncoming(blockId, blocksById, incomingByTarget, selectOwningRow,
                        topLevelSignals, selfProperties, selfId, facts, cache, inProgress);
                result = applyBlock(block, carrierIn, topLevelSignals, selfProperties, selfId, facts);
            }
        }

        inProgress.remove(blockId);
        cache.put(blockId, result);
        return result;
    }

    private static Set<UUID> gatherIncoming(UUID targetId, Map<UUID, CarrierBlock> blocksById, Map<UUID, List<UUID>> incomingByTarget,
                                             Map<UUID, Select> selectOwningRow, List<SignalBlock> topLevelSignals,
                                             Map<UUID, Object> selfProperties, UUID selfId, FactResolver facts,
                                             Map<UUID, Set<UUID>> cache, Set<UUID> inProgress) {
        Set<UUID> carrierIn = new LinkedHashSet<>();
        for (UUID sourceId : incomingByTarget.getOrDefault(targetId, List.of())) {
            carrierIn.addAll(carrierOut(sourceId, blocksById, incomingByTarget, selectOwningRow, topLevelSignals,
                    selfProperties, selfId, facts, cache, inProgress));
        }
        return carrierIn;
    }

    private static Set<UUID> applyBlock(CarrierBlock block, Set<UUID> carrierIn, List<SignalBlock> topLevelSignals,
                                         Map<UUID, Object> selfProperties, UUID selfId, FactResolver facts) {
        if (block instanceof End) return carrierIn;
        if (block instanceof AddMarkers b) {
            return applyAddMarkers(b, fires(b.gate(), topLevelSignals, selfProperties, selfId, facts), carrierIn);
        }
        if (block instanceof RemoveMarkers b) {
            if (!fires(b.gate(), topLevelSignals, selfProperties, selfId, facts)) return carrierIn;
            Set<UUID> out = new LinkedHashSet<>(carrierIn);
            out.removeAll(b.markerIds());
            return out;
        }
        if (block instanceof SetMarkers b) {
            if (!fires(b.gate(), topLevelSignals, selfProperties, selfId, facts)) return carrierIn;
            return new LinkedHashSet<>(b.markerIds());
        }
        if (block instanceof Filter f) {
            return fires(f.signal(), topLevelSignals, selfProperties, selfId, facts) ? carrierIn : Set.of();
        }
        if (block instanceof Select s) {
            // The block's own primary pin doubles as (Otherwise) -- see Select's own comment: it's
            // never an unconditional passthrough, it's gated exactly like any other carrier block.
            // Each row is independently gated too, via the exact same ordinary AddMarkers path every
            // standalone AddMarkers already uses -- nothing Select-specific about how a row fires,
            // only about where its carrierIn comes from (see carrierOut's own handling of this).
            return fires(s.gate(), topLevelSignals, selfProperties, selfId, facts) ? carrierIn : Set.of();
        }
        throw new IllegalArgumentException("Unsupported carrier block: " + block);
    }

    private static Set<UUID> applyAddMarkers(AddMarkers add, boolean fires, Set<UUID> carrierIn) {
        if (!fires) return carrierIn;
        Set<UUID> out = new LinkedHashSet<>(carrierIn);
        out.addAll(add.markerIds());
        return out;
    }

    // gate.isUnconditional() always fires without ever consulting topLevelSignals -- see Gate's own
    // comment.
    private static boolean fires(Gate gate, List<SignalBlock> topLevelSignals, Map<UUID, Object> selfProperties, UUID selfId, FactResolver facts) {
        return SignalEvaluator.evaluateGate(gate, topLevelSignals, selfProperties, selfId, facts);
    }
}

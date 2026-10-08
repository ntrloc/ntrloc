package org.ntrloc.graph.access;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Walks a pipeline's carrier graph from Start to End, resolving each carrier block's gate (if any)
// via SignalEvaluator and applying its own transform to the carrier flowing through it. A block fed
// by more than one Route merges every incoming carrier by union before applying its own transform
// (Route's own comment: "fan-in... is just implicit carrier merge"); End is the union of every
// carrier that ever arrives there -- the pipeline's final marker decision for this item.
public final class PipelineEvaluator {

    private PipelineEvaluator() {
    }

    public static Set<UUID> evaluate(Pipeline pipeline, Map<UUID, Object> selfProperties, UUID selfId) {
        Map<UUID, CarrierBlock> blocksById = new HashMap<>();
        blocksById.put(pipeline.start().id(), pipeline.start());
        blocksById.put(pipeline.end().id(), pipeline.end());
        for (CarrierBlock block : pipeline.carrierBlocks()) {
            blocksById.put(block.id(), block);
        }

        Map<UUID, List<UUID>> incomingByTarget = new HashMap<>();
        for (Route route : pipeline.routes()) {
            for (UUID target : route.inputPinIds()) {
                incomingByTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(route.outputPinId());
            }
        }

        return carrierOut(pipeline.end().id(), blocksById, incomingByTarget, pipeline.topLevelSignals(),
                selfProperties, selfId, new HashMap<>(), new HashSet<>());
    }

    private static Set<UUID> carrierOut(UUID blockId, Map<UUID, CarrierBlock> blocksById, Map<UUID, List<UUID>> incomingByTarget,
                                         List<SignalBlock> topLevelSignals, Map<UUID, Object> selfProperties, UUID selfId,
                                         Map<UUID, Set<UUID>> cache, Set<UUID> inProgress) {
        Set<UUID> cached = cache.get(blockId);
        if (cached != null) return cached;
        if (!inProgress.add(blockId)) {
            throw new IllegalStateException("Cycle detected in pipeline carrier graph at block " + blockId);
        }

        CarrierBlock block = blocksById.get(blockId);
        Set<UUID> result;
        if (block instanceof Start) {
            result = Set.of();
        } else {
            Set<UUID> carrierIn = new LinkedHashSet<>();
            for (UUID sourceId : incomingByTarget.getOrDefault(blockId, List.of())) {
                carrierIn.addAll(carrierOut(sourceId, blocksById, incomingByTarget, topLevelSignals,
                        selfProperties, selfId, cache, inProgress));
            }
            result = applyBlock(block, carrierIn, topLevelSignals, selfProperties, selfId);
        }

        inProgress.remove(blockId);
        cache.put(blockId, result);
        return result;
    }

    private static Set<UUID> applyBlock(CarrierBlock block, Set<UUID> carrierIn, List<SignalBlock> topLevelSignals,
                                         Map<UUID, Object> selfProperties, UUID selfId) {
        if (block instanceof End) return carrierIn;
        if (block instanceof AddMarkers b) {
            if (!fires(b.gate(), topLevelSignals, selfProperties, selfId)) return carrierIn;
            Set<UUID> out = new LinkedHashSet<>(carrierIn);
            out.addAll(b.markerIds());
            return out;
        }
        if (block instanceof RemoveMarkers b) {
            if (!fires(b.gate(), topLevelSignals, selfProperties, selfId)) return carrierIn;
            Set<UUID> out = new LinkedHashSet<>(carrierIn);
            out.removeAll(b.markerIds());
            return out;
        }
        if (block instanceof SetMarkers b) {
            if (!fires(b.gate(), topLevelSignals, selfProperties, selfId)) return carrierIn;
            return new LinkedHashSet<>(b.markerIds());
        }
        if (block instanceof Filter f) {
            return SignalEvaluator.evaluatePin(f.signal(), topLevelSignals, selfProperties, selfId)
                    ? carrierIn : Set.of();
        }
        throw new IllegalArgumentException("Unsupported carrier block: " + block);
    }

    // gate is nullable -- an unwired Add/Remove/SetMarkers always fires (see each block's own
    // comment); a wired gate that evaluates false means the block doesn't fire at all, so the
    // carrier passes through unaffected rather than being replaced or emptied.
    private static boolean fires(UUID gate, List<SignalBlock> topLevelSignals, Map<UUID, Object> selfProperties, UUID selfId) {
        return gate == null || SignalEvaluator.evaluatePin(gate, topLevelSignals, selfProperties, selfId);
    }
}

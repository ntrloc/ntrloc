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
        return new Evaluation(pipeline, selfProperties, selfId, facts).carrierOut(pipeline.end().id());
    }

    // One walk of one pipeline for one item. Results are cached per block, so a block feeding several
    // others is evaluated once.
    private static final class Evaluation {

        private final Map<UUID, CarrierBlock> blocksById = new HashMap<>();
        private final Map<UUID, Select> selectOwningRow = new HashMap<>();
        private final Map<UUID, List<UUID>> incomingByTarget = new HashMap<>();
        private final List<SignalBlock> topLevelSignals;
        private final Map<UUID, Object> selfProperties;
        private final UUID selfId;
        private final FactResolver facts;
        private final Map<UUID, Set<UUID>> cache = new HashMap<>();
        private final Set<UUID> inProgress = new HashSet<>();

        Evaluation(Pipeline pipeline, Map<UUID, Object> selfProperties, UUID selfId, FactResolver facts) {
            this.topLevelSignals = pipeline.topLevelSignals();
            this.selfProperties = selfProperties;
            this.selfId = selfId;
            this.facts = facts;

            blocksById.put(pipeline.start().id(), pipeline.start());
            blocksById.put(pipeline.end().id(), pipeline.end());
            // A Select's rows aren't top-level blocks, but each row is still a carrier pin that others
            // can route from. A row's carrierIn is whatever fed its Select, so each row maps back to it.
            for (CarrierBlock block : pipeline.carrierBlocks()) {
                blocksById.put(block.id(), block);
                if (block instanceof Select s) {
                    for (AddMarkers row : s.adds()) {
                        blocksById.put(row.id(), row);
                        selectOwningRow.put(row.id(), s);
                    }
                }
            }

            for (Route route : pipeline.routes()) {
                for (UUID target : route.inputPinIds()) {
                    incomingByTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(route.outputPinId());
                }
            }
        }

        Set<UUID> carrierOut(UUID blockId) {
            Set<UUID> cached = cache.get(blockId);
            if (cached != null) return cached;
            if (!inProgress.add(blockId)) {
                throw new IllegalStateException("Cycle detected in pipeline carrier graph at block " + blockId);
            }

            Set<UUID> result;
            Select owner = selectOwningRow.get(blockId);
            CarrierBlock block = blocksById.get(blockId);
            if (owner != null) {
                // A Select row: its carrierIn is whatever fed the Select itself.
                result = applyBlock(block, gatherIncoming(owner.id()));
            } else if (block instanceof Start) {
                result = Set.of();
            } else {
                result = applyBlock(block, gatherIncoming(blockId));
            }

            inProgress.remove(blockId);
            cache.put(blockId, result);
            return result;
        }

        private Set<UUID> gatherIncoming(UUID targetId) {
            Set<UUID> carrierIn = new LinkedHashSet<>();
            for (UUID sourceId : incomingByTarget.getOrDefault(targetId, List.of())) {
                carrierIn.addAll(carrierOut(sourceId));
            }
            return carrierIn;
        }

        private Set<UUID> applyBlock(CarrierBlock block, Set<UUID> carrierIn) {
            if (block instanceof End) return carrierIn;
            if (block instanceof AddMarkers b) {
                return applyAddMarkers(b, fires(b.gate()), carrierIn);
            }
            if (block instanceof RemoveMarkers b) {
                if (!fires(b.gate())) return carrierIn;
                Set<UUID> out = new LinkedHashSet<>(carrierIn);
                out.removeAll(b.markerIds());
                return out;
            }
            if (block instanceof SetMarkers b) {
                if (!fires(b.gate())) return carrierIn;
                return new LinkedHashSet<>(b.markerIds());
            }
            if (block instanceof Filter f) {
                return fires(f.signal()) ? carrierIn : Set.of();
            }
            if (block instanceof Select s) {
                // The Select's own primary pin is (Otherwise), gated like any other block. Its rows are
                // ordinary AddMarkers, handled above.
                return fires(s.gate()) ? carrierIn : Set.of();
            }
            throw new IllegalArgumentException("Unsupported carrier block: " + block);
        }

        // An unconditional gate fires without consulting topLevelSignals (see Gate).
        private boolean fires(Gate gate) {
            return SignalEvaluator.evaluateGate(gate, topLevelSignals, selfProperties, selfId, facts);
        }

        private static Set<UUID> applyAddMarkers(AddMarkers add, boolean fires, Set<UUID> carrierIn) {
            if (!fires) return carrierIn;
            Set<UUID> out = new LinkedHashSet<>(carrierIn);
            out.addAll(add.markerIds());
            return out;
        }
    }
}

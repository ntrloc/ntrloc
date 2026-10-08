package org.ntrloc.graph.access;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Evaluates one specific signal pin (a SingleOutputSignal's own id, or one PinnedPredicate's id
// inside a PredicateSignal) to a boolean, searching a pipeline's topLevelSignals for whichever
// block/pin owns that id. This is the "pull" side of the signal domain -- a carrier block's gate
// calls in here only when it actually needs the value, never the other way around.
public final class SignalEvaluator {

    private SignalEvaluator() {
    }

    public static boolean evaluatePin(UUID pinId, List<SignalBlock> topLevelSignals,
                                       Map<UUID, Object> selfProperties, UUID selfId) {
        for (SignalBlock block : topLevelSignals) {
            Boolean result = tryEvaluate(block, pinId, selfProperties, selfId);
            if (result != null) return result;
        }
        throw new IllegalArgumentException("No signal pin found for id " + pinId);
    }

    // Returns null when neither this block nor any of its nested pins is the one named by pinId --
    // lets the caller keep searching sibling top-level blocks, same idea as a failed lookup.
    private static Boolean tryEvaluate(SignalBlock block, UUID pinId, Map<UUID, Object> selfProperties, UUID selfId) {
        if (block instanceof SingleOutputSignal s) {
            if (s.id().equals(pinId)) return evaluateSingleOutput(s, selfProperties, selfId);
            if (s instanceof CompoundSignal cs) {
                for (SingleOutputSignal sub : cs.subSignals()) {
                    Boolean result = tryEvaluate(sub, pinId, selfProperties, selfId);
                    if (result != null) return result;
                }
            } else if (s instanceof CountedSignal cs) {
                return tryEvaluate(cs.subSignal(), pinId, selfProperties, selfId);
            }
            return null;
        }
        if (block instanceof PredicateSignal ps) {
            for (PinnedPredicate pinned : ps.predicates()) {
                if (pinned.id().equals(pinId)) {
                    Object value = ContextResolver.resolve(ps.context(), selfProperties);
                    return PredicateEvaluator.evaluate(pinned.predicate(), value, selfId);
                }
            }
        }
        return null;
    }

    private static boolean evaluateSingleOutput(SingleOutputSignal s, Map<UUID, Object> selfProperties, UUID selfId) {
        if (s instanceof Signal sig) {
            Object value = ContextResolver.resolve(sig.context(), selfProperties);
            return PredicateEvaluator.evaluate(sig.predicate(), value, selfId);
        }
        if (s instanceof CompoundSignal cs) return evaluateCompound(cs, selfProperties, selfId);
        if (s instanceof CountedSignal) {
            throw new UnsupportedOperationException("CountedSignal evaluation needs multi-instance traversal, not yet supported");
        }
        throw new IllegalArgumentException("Unsupported SingleOutputSignal: " + s);
    }

    // Ordered, short-circuited evaluation -- matches CompoundSignal's own comment on why subSignals
    // is a List, not a Set.
    private static boolean evaluateCompound(CompoundSignal s, Map<UUID, Object> selfProperties, UUID selfId) {
        boolean any = s.combinator() == Combinator.ANY;
        for (SingleOutputSignal sub : s.subSignals()) {
            boolean value = evaluateSingleOutput(sub, selfProperties, selfId);
            if (any == value) return any;
        }
        return !any;
    }
}

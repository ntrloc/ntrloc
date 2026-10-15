package org.ntrloc.graph.access;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Resolves a Context chain to the value a Predicate should test, against the item currently being
// evaluated. Only "one of this item's own scalar properties" (a StepToProperty terminating in
// Self, no `each`) is implemented so far -- link traversal, nested object properties, state
// machine lookups, and each-iteration all need real register reads and are built scenario by
// scenario as the evaluator grows; see the marker decision pipeline design notes.
public final class ContextResolver {

    private ContextResolver() {
    }

    public static Object resolve(Context context, Map<UUID, Object> selfProperties) {
        if (context instanceof StepToProperty step && !step.each() && step.next() instanceof Self) {
            return selfProperties.get(step.propertyId());
        }
        throw new UnsupportedOperationException("ContextResolver does not yet support " + context);
    }

    // One leaf reached by walking a Context chain, flattening every `each` step along the way --
    // itemId is the item the chain was actually standing on when it produced value, which is what a
    // CountedPredicate's own subPredicate/countPredicate need when THEY declare a fresh subject of
    // their own (rather than inheriting): that fresh subject resolves relative to *this* item, not
    // the pipeline's original root. See CountedPredicate's own comment and SignalEvaluator's own
    // evaluateCountedPredicate for how this gets used.
    public record Instance(UUID itemId, Object value) {
    }

    // The multi-instance-traversal counterpart to resolve() above -- always returns a list, flattening
    // one branch per instance at every `each` step and continuing deterministically (asserting at
    // most one target) through a non-each step. A chain with zero `each` steps anywhere always
    // returns exactly one Instance (or zero, if a deterministic step along the way had nothing to
    // step to -- a dead branch, not an error): this is a strict superset of what resolve() answers,
    // kept as a second method rather than a replacement so every existing self-property-only caller
    // (and its tests) keeps working completely unchanged.
    //
    // itemId/itemProperties is the item the chain is CURRENTLY standing on -- the pipeline's own root
    // on the first call, and whatever item a prior StepToItem landed on for every recursive call
    // after that. facts answers everything beyond that one item's own already-known properties.
    public static List<Instance> resolveInstances(Context context, UUID itemId, Map<UUID, Object> itemProperties, FactResolver facts) {
        if (context instanceof Self) {
            return List.of(new Instance(itemId, itemId));
        }
        if (context instanceof StepToProperty step) {
            if (step.each()) {
                throw new UnsupportedOperationException("ContextResolver does not yet support each on " + context);
            }
            if (!(step.next() instanceof Self)) {
                throw new UnsupportedOperationException("ContextResolver does not yet support " + context);
            }
            return List.of(new Instance(itemId, itemProperties.get(step.propertyId())));
        }
        if (context instanceof StepToStateMachine step) {
            return List.of(new Instance(itemId, facts.currentStateId(itemId, step.stateMachineId())));
        }
        if (context instanceof StepToItem step) {
            List<UUID> targets = facts.linkedItemIds(itemId, step.linkPerspectiveId());
            if (!step.each()) {
                if (targets.size() > 1) {
                    throw new UnsupportedOperationException(
                            "Flattening a to-many step without `each` is not yet supported: " + context);
                }
                if (targets.isEmpty()) {
                    return List.of();
                }
                UUID target = targets.get(0);
                return resolveInstances(step.next(), target, facts.propertiesOfItem(target), facts);
            }
            List<Instance> results = new ArrayList<>();
            for (UUID target : targets) {
                results.addAll(resolveInstances(step.next(), target, facts.propertiesOfItem(target), facts));
            }
            return results;
        }
        // StepToLink -- reading a link's own properties, rather than stepping past it to the item on
        // the far end -- is a deliberately separate, still-unsupported piece of work; see
        // FactResolver's own comment.
        throw new UnsupportedOperationException("ContextResolver does not yet support " + context);
    }
}

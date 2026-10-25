package org.ntrloc.graph.access;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Evaluates one specific signal pin (a Condition's own id, whether it sits standalone at the
// pipeline's top level or nested inside a ConditionGroup) to a boolean, searching a pipeline's
// topLevelSignals for whichever condition owns that id. This is the "pull" side of the signal domain
// -- a carrier block's gate calls in here only when it actually needs the value, never the other way
// around.
public final class SignalEvaluator {

    private SignalEvaluator() {
    }

    // Legacy overloads -- every existing caller (and its tests) keeps exactly the behavior it always
    // had: a context needing a real register read (anything FactResolver would otherwise answer)
    // still throws UnsupportedOperationException, since there's no resolver here to ask.
    public static boolean evaluateGate(Gate gate, List<SignalBlock> topLevelSignals,
                                        Map<UUID, Object> selfProperties, UUID selfId) {
        return evaluateGate(gate, topLevelSignals, selfProperties, selfId, FactResolver.unsupported());
    }

    public static boolean evaluatePin(UUID pinId, List<SignalBlock> topLevelSignals,
                                       Map<UUID, Object> selfProperties, UUID selfId) {
        return evaluatePin(pinId, topLevelSignals, selfProperties, selfId, FactResolver.unsupported());
    }

    // A carrier block's own Gate: a list of condition pins plus how many of them need to hold,
    // combined with the same short-circuited quantity logic a TotalityPredicate uses for its own
    // sub-predicates -- see evaluateQuantity. gate.isUnconditional() always fires, regardless of
    // quantity, without ever consulting topLevelSignals at all.
    public static boolean evaluateGate(Gate gate, List<SignalBlock> topLevelSignals,
                                        Map<UUID, Object> selfProperties, UUID selfId, FactResolver facts) {
        if (gate.isUnconditional()) return true;
        return evaluateQuantity(gate.quantity(), gate.conditionOutputPinIds(),
                pinId -> evaluatePin(pinId, topLevelSignals, selfProperties, selfId, facts));
    }

    public static boolean evaluatePin(UUID pinId, List<SignalBlock> topLevelSignals,
                                       Map<UUID, Object> selfProperties, UUID selfId, FactResolver facts) {
        for (SignalBlock block : topLevelSignals) {
            switch (block) {
                case Condition c -> {
                    if (c.id().equals(pinId)) return evaluateCondition(c, null, selfId, selfProperties, selfId, facts);
                }
                case ConditionGroup g -> {
                    for (Condition c : g.conditions()) {
                        // A member condition with no subject of its own inherits the group's -- the same
                        // DRY/inheritance relationship a predicate has with whatever contains it.
                        if (c.id().equals(pinId)) return evaluateCondition(c, g.subject(), selfId, selfProperties, selfId, facts);
                    }
                }
            }
        }
        throw new IllegalArgumentException("No signal pin found for id " + pinId);
    }

    // inherited is the subject a standalone condition has nothing to inherit from (null), or the
    // enclosing ConditionGroup's own subject. currentItemId/currentProperties is whatever item the
    // current evaluation scope is rooted at -- the pipeline's own root item at the top level, or
    // (once evaluateCountedPredicate starts enumerating instances) whichever item one of those
    // instances landed on; selfId never changes, and is only ever read by IdentityPredicate to
    // compare an already-resolved id back against the pipeline's true root.
    private static boolean evaluateCondition(Condition condition, Context inherited,
                                              UUID currentItemId, Map<UUID, Object> currentProperties,
                                              UUID selfId, FactResolver facts) {
        // Falls all the way back to Self only once nothing anywhere above actually supplied one -- the
        // one root default nothing could ever further inherit from, the same way an un-contexted
        // signal always resolved against the item itself.
        Context subject = condition.subject();
        if (subject == null) {
            subject = inherited != null ? inherited : new Self();
        }
        return evaluatePredicate(condition.predicate(), subject, currentItemId, currentProperties, selfId, facts);
    }

    // subject is the inherited subject from whatever contains this predicate (a Condition, or an
    // enclosing TotalityPredicate/CountedPredicate's own effective subject) -- used only when the
    // predicate has none of its own. See Predicate's own comment on why this is universal, not
    // special-cased per kind.
    private static boolean evaluatePredicate(Predicate predicate, Context inherited,
                                              UUID currentItemId, Map<UUID, Object> currentProperties,
                                              UUID selfId, FactResolver facts) {
        Context subject = predicate.subject() != null ? predicate.subject() : inherited;
        return switch (predicate) {
            case TotalityPredicate t -> evaluateQuantity(t.quantity(), t.subPredicates(),
                    sub -> evaluatePredicate(sub, subject, currentItemId, currentProperties, selfId, facts));
            case CountedPredicate cp -> evaluateCountedPredicate(cp, subject, currentItemId, currentProperties, selfId, facts);
            default -> evaluateAgainstSingleValue(predicate, subject, currentItemId, currentProperties, selfId, facts);
        };
    }

    // Every other predicate kind tests exactly one resolved value -- see PredicateEvaluator.
    private static boolean evaluateAgainstSingleValue(Predicate predicate, Context subject,
                                                      UUID currentItemId, Map<UUID, Object> currentProperties,
                                                      UUID selfId, FactResolver facts) {
        List<ContextResolver.Instance> instances = ContextResolver.resolveInstances(subject, currentItemId, currentProperties, facts);
        if (instances.size() > 1) {
            // An each-containing subject outside a CountedPredicate has nowhere to put more than one
            // resolved value -- CountedPredicate's own constructor requires `each` in the chain
            // precisely because it's the only predicate kind built to consume more than one.
            throw new IllegalStateException("Subject resolved to more than one instance outside a CountedPredicate: " + subject);
        }
        Object value = instances.isEmpty() ? null : instances.getFirst().value();
        return PredicateEvaluator.evaluate(predicate, value, selfId);
    }

    // The mechanism CountedPredicate always needed and never had: enumerate every instance cp's own
    // subject reaches (flattening each `each` step along the way -- see ContextResolver.resolveInstances),
    // evaluate subPredicate once per instance, and test the fired count against countPredicate.
    // subPredicate/countPredicate "inherit" cp's own subject the same way any nested predicate
    // inherits an enclosing one when it declares none of its own (Predicate's own universal-subject
    // rule) -- except here, inheriting means "use this specific instance's own already-resolved
    // value directly," never "re-walk the shared, still-multi-valued chain again." That's what keeps
    // each instance's evaluation correlated to that one instance instead of the whole enumerated set.
    private static boolean evaluateCountedPredicate(CountedPredicate cp, Context subject,
                                                     UUID currentItemId, Map<UUID, Object> currentProperties,
                                                     UUID selfId, FactResolver facts) {
        List<ContextResolver.Instance> instances = ContextResolver.resolveInstances(subject, currentItemId, currentProperties, facts);
        int fired = 0;
        for (ContextResolver.Instance instance : instances) {
            if (evaluateAtInstance(cp.subPredicate(), instance, selfId, facts)) fired++;
        }
        // countPredicate never has a meaningful subject of its own to inherit -- it always compares
        // directly against the fired count, an ordinary Integer fed through the exact same
        // NumberPredicate dispatch every other numeric comparison already uses.
        return PredicateEvaluator.evaluate(cp.countPredicate(), fired, selfId);
    }

    // A predicate with no subject of its own is tested directly against this one instance's already-
    // resolved terminal value (no further Context resolution needed or possible -- that value IS the
    // answer this instance produced). One that declares its own subject resolves it fresh, rooted at
    // this instance's own item -- e.g. a CountedPredicate nested inside another's per-instance scope,
    // stepping further from "the current photo" rather than flattening from the top (see
    // CountedPredicate's own comment on why getting this right is what makes correlation work).
    private static boolean evaluateAtInstance(Predicate predicate, ContextResolver.Instance instance, UUID selfId, FactResolver facts) {
        if (predicate.subject() != null) {
            return evaluatePredicate(predicate, null, instance.itemId(), facts.propertiesOfItem(instance.itemId()), selfId, facts);
        }
        if (predicate instanceof TotalityPredicate || predicate instanceof CountedPredicate) {
            // Nothing of its own, and nothing further above to inherit at this fresh per-instance
            // scope -- the same "fall back to Self" root default evaluateCondition already applies.
            return evaluatePredicate(predicate, new Self(), instance.itemId(), facts.propertiesOfItem(instance.itemId()), selfId, facts);
        }
        return PredicateEvaluator.evaluate(predicate, instance.value(), selfId);
    }

    // Shared by TotalityPredicate's own sub-predicates and a carrier block's own Gate -- both combine
    // a list of booleans by ANY/ALL/NONE, evaluated lazily one at a time via `test`, short-circuiting
    // as soon as the quantity's result is determined, the same way ordinary boolean short-circuiting
    // works anywhere else. java.util.function.Predicate is fully qualified here deliberately, to avoid
    // colliding with this package's own Predicate.
    private static <T> boolean evaluateQuantity(Quantity quantity, List<T> items, java.util.function.Predicate<T> test) {
        for (T item : items) {
            boolean value = test.test(item);
            switch (quantity) {
                case ANY -> { if (value) return true; }
                case ALL -> { if (!value) return false; }
                case NONE -> { if (value) return false; }
            }
        }
        return quantity != Quantity.ANY;
    }
}

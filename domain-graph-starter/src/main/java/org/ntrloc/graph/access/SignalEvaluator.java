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

    // A carrier block's own Gate: a list of condition pins plus how many of them need to hold,
    // combined with the same short-circuited quantity logic a TotalityPredicate uses for its own
    // sub-predicates -- see evaluateQuantity. gate.isUnconditional() always fires, regardless of
    // quantity, without ever consulting topLevelSignals at all.
    public static boolean evaluateGate(Gate gate, List<SignalBlock> topLevelSignals,
                                        Map<UUID, Object> selfProperties, UUID selfId) {
        if (gate.isUnconditional()) return true;
        return evaluateQuantity(gate.quantity(), gate.conditionOutputPinIds(),
                pinId -> evaluatePin(pinId, topLevelSignals, selfProperties, selfId));
    }

    public static boolean evaluatePin(UUID pinId, List<SignalBlock> topLevelSignals,
                                       Map<UUID, Object> selfProperties, UUID selfId) {
        for (SignalBlock block : topLevelSignals) {
            if (block instanceof Condition c && c.id().equals(pinId)) {
                return evaluateCondition(c, null, selfProperties, selfId);
            }
            if (block instanceof ConditionGroup g) {
                for (Condition c : g.conditions()) {
                    // A member condition with no subject of its own inherits the group's -- the same
                    // DRY/inheritance relationship a predicate has with whatever contains it.
                    if (c.id().equals(pinId)) return evaluateCondition(c, g.subject(), selfProperties, selfId);
                }
            }
        }
        throw new IllegalArgumentException("No signal pin found for id " + pinId);
    }

    // inherited is the subject a standalone condition has nothing to inherit from (null), or the
    // enclosing ConditionGroup's own subject.
    private static boolean evaluateCondition(Condition condition, Context inherited,
                                              Map<UUID, Object> selfProperties, UUID selfId) {
        // Falls all the way back to Self only once nothing anywhere above actually supplied one -- the
        // one root default nothing could ever further inherit from, the same way an un-contexted
        // signal always resolved against the item itself.
        Context subject = condition.subject() != null ? condition.subject()
                : inherited != null ? inherited : new Self();
        return evaluatePredicate(condition.predicate(), subject, selfProperties, selfId);
    }

    // subject is the inherited subject from whatever contains this predicate (a Condition, or an
    // enclosing TotalityPredicate/CountedPredicate's own effective subject) -- used only when the
    // predicate has none of its own. See Predicate's own comment on why this is universal, not
    // special-cased per kind.
    private static boolean evaluatePredicate(Predicate predicate, Context inherited,
                                              Map<UUID, Object> selfProperties, UUID selfId) {
        Context subject = predicate.subject() != null ? predicate.subject() : inherited;
        if (predicate instanceof TotalityPredicate t) {
            return evaluateQuantity(t.quantity(), t.subPredicates(),
                    sub -> evaluatePredicate(sub, subject, selfProperties, selfId));
        }
        if (predicate instanceof CountedPredicate) {
            throw new UnsupportedOperationException("CountedPredicate evaluation needs multi-instance traversal, not yet supported");
        }
        Object value = ContextResolver.resolve(subject, selfProperties);
        return PredicateEvaluator.evaluate(predicate, value, selfId);
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

package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalEvaluatorTest {

    private final UUID selfId = UUID.randomUUID();

    private Condition propertyCondition(UUID propertyId, String equalsValue) {
        return new Condition(UUID.randomUUID(), new StepToProperty(propertyId, false, new Self()),
                new StringEqualsPredicate(null, equalsValue));
    }

    @Test
    void evaluatesABareConditionPinDirectly() {
        UUID propertyId = UUID.randomUUID();
        Condition condition = propertyCondition(propertyId, "IN_STOCK");

        boolean result = SignalEvaluator.evaluatePin(condition.id(), List.of(condition), Map.of(propertyId, "IN_STOCK"), selfId);

        assertThat(result).isTrue();
    }

    @Test
    void evaluatesATotalityPredicatesCondition_anyShortCircuitsOnTheFirstTrue() {
        UUID propertyA = UUID.randomUUID();
        UUID propertyB = UUID.randomUUID();
        Predicate a = new StringEqualsPredicate(new StepToProperty(propertyA, false, new Self()), "X");
        Predicate b = new StringEqualsPredicate(new StepToProperty(propertyB, false, new Self()), "Y");
        Condition any = new Condition(UUID.randomUUID(), null, new TotalityPredicate(null, Quantity.ANY, List.of(a, b)));

        boolean result = SignalEvaluator.evaluatePin(any.id(), List.of(any), Map.of(propertyA, "X", propertyB, "nope"), selfId);

        assertThat(result).isTrue();
    }

    @Test
    void aPredicatesOwnSubjectOverridesWhatItWouldOtherwiseInherit() {
        UUID propertyA = UUID.randomUUID();
        UUID propertyB = UUID.randomUUID();
        // The condition establishes propertyA, but its one predicate declares its own subject
        // (propertyB) instead -- that override must win, not the inherited one.
        Condition condition = new Condition(UUID.randomUUID(), new StepToProperty(propertyA, false, new Self()),
                new StringEqualsPredicate(new StepToProperty(propertyB, false, new Self()), "Y"));

        boolean result = SignalEvaluator.evaluatePin(condition.id(), List.of(condition),
                Map.of(propertyA, "Y", propertyB, "Y"), selfId);

        assertThat(result).isTrue();
        boolean usesPropertyB = SignalEvaluator.evaluatePin(condition.id(), List.of(condition),
                Map.of(propertyA, "Y", propertyB, "not Y"), selfId);
        assertThat(usesPropertyB).isFalse();
    }

    @Test
    void totalityPredicatesSubPredicatesCanEachDeclareADifferentSubject() {
        // The motivating gap this whole model revision closed: "firstName is not null" and "photos is
        // not empty" as siblings, each addressing a different property, grouped under one condition.
        UUID firstName = UUID.randomUUID();
        UUID photos = UUID.randomUUID();
        Predicate firstNameIsNotNull = new StringExistsPredicate(new StepToProperty(firstName, false, new Self()));
        Predicate photosIsNotEmpty = new StringListIsNotEmptyPredicate(new StepToProperty(photos, false, new Self()));
        Condition condition = new Condition(UUID.randomUUID(), null,
                new TotalityPredicate(null, Quantity.ALL, List.of(firstNameIsNotNull, photosIsNotEmpty)));

        boolean result = SignalEvaluator.evaluatePin(condition.id(), List.of(condition),
                Map.of(firstName, "Ada", photos, List.of("photo1")), selfId);

        assertThat(result).isTrue();
    }

    @Test
    void evaluatesOneConditionInsideAConditionGroup_independentlyOfItsSiblings() {
        UUID propertyId = UUID.randomUUID();
        Condition low = new Condition(UUID.randomUUID(), null, new NumberPredicate(null, ComparisonOperator.LESS_THAN, 10, null));
        Condition high = new Condition(UUID.randomUUID(), null, new NumberPredicate(null, ComparisonOperator.GREATER_THAN_OR_EQUAL, 10, null));
        ConditionGroup group = new ConditionGroup(new StepToProperty(propertyId, false, new Self()), List.of(low, high));

        Map<UUID, Object> props = Map.of(propertyId, 25);

        assertThat(SignalEvaluator.evaluatePin(low.id(), List.of(group), props, selfId)).isFalse();
        assertThat(SignalEvaluator.evaluatePin(high.id(), List.of(group), props, selfId)).isTrue();
    }

    @Test
    void evaluateGate_unconditionalAlwaysFiresWithoutConsultingAnyPin() {
        assertThat(SignalEvaluator.evaluateGate(Gate.UNCONDITIONAL, List.of(), Map.of(), selfId)).isTrue();
    }

    @Test
    void evaluateGate_combinesSeveralConditionPinsByQuantity_notJustOne() {
        // A gate was always going to be able to combine more than one condition pin directly, without
        // needing a TotalityPredicate wrapping them purely to produce one combined pin -- that
        // mechanism solves a different problem (predicates within one condition's own fact-reading),
        // not this one (several already-resolved conditions deciding whether a carrier block fires).
        UUID propertyA = UUID.randomUUID();
        UUID propertyB = UUID.randomUUID();
        Condition a = propertyCondition(propertyA, "X");
        Condition b = propertyCondition(propertyB, "Y");
        List<SignalBlock> signals = List.of(a, b);
        Gate all = new Gate(List.of(a.id(), b.id()), Quantity.ALL);
        Gate any = new Gate(List.of(a.id(), b.id()), Quantity.ANY);
        Gate none = new Gate(List.of(a.id(), b.id()), Quantity.NONE);

        Map<UUID, Object> bothMatch = Map.of(propertyA, "X", propertyB, "Y");
        Map<UUID, Object> onlyOneMatches = Map.of(propertyA, "X", propertyB, "nope");
        Map<UUID, Object> neitherMatches = Map.of(propertyA, "nope", propertyB, "nope");

        assertThat(SignalEvaluator.evaluateGate(all, signals, bothMatch, selfId)).isTrue();
        assertThat(SignalEvaluator.evaluateGate(all, signals, onlyOneMatches, selfId)).isFalse();
        assertThat(SignalEvaluator.evaluateGate(any, signals, onlyOneMatches, selfId)).isTrue();
        assertThat(SignalEvaluator.evaluateGate(any, signals, neitherMatches, selfId)).isFalse();
        assertThat(SignalEvaluator.evaluateGate(none, signals, neitherMatches, selfId)).isTrue();
        assertThat(SignalEvaluator.evaluateGate(none, signals, onlyOneMatches, selfId)).isFalse();
    }

    @Test
    void anUnknownPinId_throws() {
        assertThatThrownBy(() -> SignalEvaluator.evaluatePin(UUID.randomUUID(), List.of(), Map.of(), selfId))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

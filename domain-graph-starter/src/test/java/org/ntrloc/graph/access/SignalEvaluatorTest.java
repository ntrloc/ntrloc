package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalEvaluatorTest {

    private final UUID selfId = UUID.randomUUID();

    private Signal propertySignal(UUID propertyId, String equalsValue) {
        return new Signal(UUID.randomUUID(), new StepToProperty(propertyId, false, new Self()),
                null, new StringEqualsPredicate(equalsValue));
    }

    @Test
    void evaluatesABareSignalPinDirectly() {
        UUID propertyId = UUID.randomUUID();
        Signal signal = propertySignal(propertyId, "IN_STOCK");

        boolean result = SignalEvaluator.evaluatePin(signal.id(), List.of(signal), Map.of(propertyId, "IN_STOCK"), selfId);

        assertThat(result).isTrue();
    }

    @Test
    void evaluatesACompoundSignalsOwnPin_anyShortCircuitsOnTheFirstTrue() {
        UUID propertyA = UUID.randomUUID();
        UUID propertyB = UUID.randomUUID();
        Signal a = propertySignal(propertyA, "X");
        Signal b = propertySignal(propertyB, "Y");
        CompoundSignal any = new CompoundSignal(UUID.randomUUID(), Combinator.ANY, List.of(a, b));

        boolean result = SignalEvaluator.evaluatePin(any.id(), List.of(any), Map.of(propertyA, "X", propertyB, "nope"), selfId);

        assertThat(result).isTrue();
    }

    @Test
    void evaluatesANestedSubSignalsPin_notJustTheCompoundsOwnPin() {
        UUID propertyA = UUID.randomUUID();
        UUID propertyB = UUID.randomUUID();
        Signal a = propertySignal(propertyA, "X");
        Signal b = propertySignal(propertyB, "Y");
        CompoundSignal compound = new CompoundSignal(UUID.randomUUID(), Combinator.ALL, List.of(a, b));

        boolean result = SignalEvaluator.evaluatePin(b.id(), List.of(compound), Map.of(propertyB, "Y"), selfId);

        assertThat(result).isTrue();
    }

    @Test
    void evaluatesOnePinnedPredicateInsideAPredicateSignal_independentlyOfItsSiblings() {
        UUID propertyId = UUID.randomUUID();
        PinnedPredicate low = new PinnedPredicate(UUID.randomUUID(), new NumberPredicate(ComparisonOperator.LESS_THAN, 10, null));
        PinnedPredicate high = new PinnedPredicate(UUID.randomUUID(), new NumberPredicate(ComparisonOperator.GREATER_THAN_OR_EQUAL, 10, null));
        PredicateSignal signal = new PredicateSignal(new StepToProperty(propertyId, false, new Self()), null, List.of(low, high));

        Map<UUID, Object> props = Map.of(propertyId, 25);

        assertThat(SignalEvaluator.evaluatePin(low.id(), List.of(signal), props, selfId)).isFalse();
        assertThat(SignalEvaluator.evaluatePin(high.id(), List.of(signal), props, selfId)).isTrue();
    }

    @Test
    void anUnknownPinId_throws() {
        assertThatThrownBy(() -> SignalEvaluator.evaluatePin(UUID.randomUUID(), List.of(), Map.of(), selfId))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

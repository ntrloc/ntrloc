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

    // --- CountedPredicate: multi-instance traversal ---

    @Test
    void aCountedPredicate_withNoFactResolver_stillThrows_sameGapItAlwaysHad() {
        // The legacy (FactResolver-less) overloads must keep throwing exactly as they always did --
        // this is the literal exception the live Photographer pipeline surfaced before this round of
        // work, reproduced here as a tripwire against ever silently regressing it.
        UUID perspectiveId = UUID.randomUUID();
        Predicate counted = new CountedPredicate(new StepToItem(perspectiveId, true, new Self()),
                new EqualsSelfPredicate(null), new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 0, null));
        Condition condition = new Condition(UUID.randomUUID(), null, counted);

        assertThatThrownBy(() -> SignalEvaluator.evaluatePin(condition.id(), List.of(condition), Map.of(), selfId))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aCountedPredicate_countsHowManyLinkedInstancesSatisfyTheSubPredicate() {
        UUID perspectiveId = UUID.randomUUID();
        UUID photoA = UUID.randomUUID();
        UUID photoB = UUID.randomUUID();
        UUID photoC = UUID.randomUUID();
        UUID genre = UUID.randomUUID();
        FactResolver facts = new InMemoryFactResolver()
                .link(selfId, perspectiveId, photoA)
                .link(selfId, perspectiveId, photoB)
                .link(selfId, perspectiveId, photoC)
                .properties(photoA, Map.of(genre, "NATURE"))
                .properties(photoB, Map.of(genre, "PORTRAIT"))
                .properties(photoC, Map.of(genre, "NATURE"));

        // subPredicate has its own subject (a fresh property read on each linked photo, not an
        // inherited terminal value) -- exercises evaluateAtInstance's "declares its own subject"
        // branch, not just the common "inherits, use the leaf value directly" one.
        Predicate counted = new CountedPredicate(new StepToItem(perspectiveId, true, new Self()),
                new StringEqualsPredicate(new StepToProperty(genre, false, new Self()), "NATURE"),
                new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 0, null));
        Condition condition = new Condition(UUID.randomUUID(), null, counted);

        assertThat(SignalEvaluator.evaluatePin(condition.id(), List.of(condition), Map.of(), selfId, facts)).isTrue();
    }

    @Test
    void aCountedPredicate_withNoInstancesSatisfyingTheSubPredicate_failsTheCountGreaterThanZero() {
        UUID perspectiveId = UUID.randomUUID();
        UUID photoA = UUID.randomUUID();
        UUID genre = UUID.randomUUID();
        FactResolver facts = new InMemoryFactResolver()
                .link(selfId, perspectiveId, photoA)
                .properties(photoA, Map.of(genre, "PORTRAIT"));

        Predicate counted = new CountedPredicate(new StepToItem(perspectiveId, true, new Self()),
                new StringEqualsPredicate(new StepToProperty(genre, false, new Self()), "NATURE"),
                new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 0, null));
        Condition condition = new Condition(UUID.randomUUID(), null, counted);

        assertThat(SignalEvaluator.evaluatePin(condition.id(), List.of(condition), Map.of(), selfId, facts)).isFalse();
    }

    @Test
    void aCountedPredicate_withAnEmptyLink_countsZeroInstances_ratherThanThrowing() {
        UUID perspectiveId = UUID.randomUUID();
        Predicate counted = new CountedPredicate(new StepToItem(perspectiveId, true, new Self()),
                new EqualsSelfPredicate(null), new NumberPredicate(null, ComparisonOperator.EQUALS, 0, null));
        Condition condition = new Condition(UUID.randomUUID(), null, counted);

        assertThat(SignalEvaluator.evaluatePin(condition.id(), List.of(condition), Map.of(), selfId, new InMemoryFactResolver())).isTrue();
    }

    @Test
    void aCountedPredicate_withTwoNestedEachSteps_reproducesTheLivePhotographerScenario() {
        // The exact shape the live Photographer marker-decision pipeline authored: self -> photos
        // (each) -> portfolios (each) -> state machine, subPredicate STATE_EQUALS with no subject of
        // its own (inherits the per-(photo,portfolio)-pair resolved state directly), countPredicate
        // "count > 0". This is the scenario that threw UnsupportedOperationException in production
        // before this round of work.
        UUID photosPerspective = UUID.randomUUID();
        UUID portfoliosPerspective = UUID.randomUUID();
        UUID stateMachineId = UUID.randomUUID();
        UUID publishedStateId = UUID.randomUUID();
        UUID draftStateId = UUID.randomUUID();
        UUID photoA = UUID.randomUUID();
        UUID photoB = UUID.randomUUID();
        UUID portfolio1 = UUID.randomUUID();
        UUID portfolio2 = UUID.randomUUID();

        FactResolver facts = new InMemoryFactResolver()
                .link(selfId, photosPerspective, photoA)
                .link(selfId, photosPerspective, photoB)
                .link(photoA, portfoliosPerspective, portfolio1)
                .link(photoB, portfoliosPerspective, portfolio2)
                .state(portfolio1, stateMachineId, draftStateId)
                .state(portfolio2, stateMachineId, publishedStateId);

        Context subject = new StepToItem(photosPerspective, true,
                new StepToItem(portfoliosPerspective, true, new StepToStateMachine(stateMachineId)));
        Predicate counted = new CountedPredicate(subject, new StateEqualsPredicate(null, publishedStateId),
                new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 0, null));
        Condition condition = new Condition(UUID.randomUUID(), null, counted);

        assertThat(SignalEvaluator.evaluatePin(condition.id(), List.of(condition), Map.of(), selfId, facts)).isTrue();

        // Neither portfolio published -- the same pipeline now correctly reports false, not an
        // exception.
        FactResolver noneMatch = new InMemoryFactResolver()
                .link(selfId, photosPerspective, photoA)
                .link(photoA, portfoliosPerspective, portfolio1)
                .state(portfolio1, stateMachineId, draftStateId);
        assertThat(SignalEvaluator.evaluatePin(condition.id(), List.of(condition), Map.of(), selfId, noneMatch)).isFalse();
    }

    @Test
    void aCountedPredicate_withAnArbitraryThreshold_comparesTheFiredCountNotJustAnyOrNone() {
        // subPredicate declares its own subject (a fresh property read on each linked photo) rather
        // than inheriting a terminal value -- exercises evaluateAtInstance's "has its own subject"
        // branch, same as the genre-NATURE test above, just with a threshold other than > 0.
        UUID perspectiveId = UUID.randomUUID();
        UUID tag = UUID.randomUUID();
        UUID photoA = UUID.randomUUID();
        UUID photoB = UUID.randomUUID();
        UUID photoC = UUID.randomUUID();
        FactResolver facts = new InMemoryFactResolver()
                .link(selfId, perspectiveId, photoA)
                .link(selfId, perspectiveId, photoB)
                .link(selfId, perspectiveId, photoC)
                .properties(photoA, Map.of(tag, "x"))
                .properties(photoB, Map.of(tag, "x"))
                .properties(photoC, Map.of(tag, "x"));
        Predicate taggedOnEachPhoto = new StringExistsPredicate(new StepToProperty(tag, false, new Self()));

        Predicate counted = new CountedPredicate(new StepToItem(perspectiveId, true, new Self()),
                taggedOnEachPhoto, new NumberPredicate(null, ComparisonOperator.GREATER_THAN_OR_EQUAL, 3, null));
        Condition condition = new Condition(UUID.randomUUID(), null, counted);

        assertThat(SignalEvaluator.evaluatePin(condition.id(), List.of(condition), Map.of(), selfId, facts)).isTrue();

        Predicate countedTooFew = new CountedPredicate(new StepToItem(perspectiveId, true, new Self()),
                taggedOnEachPhoto, new NumberPredicate(null, ComparisonOperator.GREATER_THAN_OR_EQUAL, 4, null));
        Condition conditionTooFew = new Condition(UUID.randomUUID(), null, countedTooFew);
        assertThat(SignalEvaluator.evaluatePin(conditionTooFew.id(), List.of(conditionTooFew), Map.of(), selfId, facts)).isFalse();
    }

    @Test
    void aValuePredicateWhoseSubjectResolvesToMoreThanOneInstance_outsideACountedPredicate_throws() {
        UUID perspectiveId = UUID.randomUUID();
        FactResolver facts = new InMemoryFactResolver()
                .link(selfId, perspectiveId, UUID.randomUUID())
                .link(selfId, perspectiveId, UUID.randomUUID());
        Condition condition = new Condition(UUID.randomUUID(), new StepToItem(perspectiveId, true, new Self()),
                new EqualsSelfPredicate(null));

        assertThatThrownBy(() -> SignalEvaluator.evaluatePin(condition.id(), List.of(condition), Map.of(), selfId, facts))
                .isInstanceOf(IllegalStateException.class);
    }
}

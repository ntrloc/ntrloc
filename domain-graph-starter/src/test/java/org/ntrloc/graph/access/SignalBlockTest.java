package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

// Pins down the arity table from "Signals: reading facts" / "Object shape": Signal is exactly one
// predicate, PredicateSignal/CompoundSignal need 2+ (there's nothing to combine at 1), CountedSignal
// wraps exactly one sub-signal and its context must actually iterate something. Every SingleOutputSignal
// carries its own pin id; PredicateSignal has none of its own, only its PinnedPredicates do.
class SignalBlockTest {

    @Test
    void signalHoldsExactlyOnePredicate() {
        Signal signal = new Signal(UUID.randomUUID(), new Self(), null, new StringEqualsPredicate("CLUBS"));
        assertThat(signal.predicate()).isInstanceOf(StringEqualsPredicate.class);
    }

    @Test
    void predicateSignalRejectsFewerThanTwoPredicates() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PredicateSignal(new Self(), "property", List.of(
                        new PinnedPredicate(UUID.randomUUID(), new StringEqualsPredicate("CLUBS")))));
    }

    @Test
    void predicateSignalAllowsTwoOrMorePredicatesEachWithItsOwnPin() {
        PinnedPredicate nature = new PinnedPredicate(UUID.randomUUID(), new StringListIncludesPredicate("NATURE"));
        PinnedPredicate weddings = new PinnedPredicate(UUID.randomUUID(), new StringListIncludesPredicate("WEDDINGS"));

        PredicateSignal signal = new PredicateSignal(new Self(), "genres", List.of(nature, weddings));

        assertThat(signal.predicates()).hasSize(2);
        assertThat(signal.predicates().get(0).id()).isNotEqualTo(signal.predicates().get(1).id());
    }

    @Test
    void predicateSignalIsNotASingleOutputSignal() {
        // Multi-pin -- can't stand in wherever "a signal" is expected, so it can't appear as a
        // CompoundSignal/CountedSignal sub-signal. Enforced by the type system: PredicateSignal simply
        // doesn't implement SingleOutputSignal, so this wouldn't even compile if attempted.
        PredicateSignal signal = new PredicateSignal(new Self(), "genres", List.of(
                new PinnedPredicate(UUID.randomUUID(), new StringListIncludesPredicate("NATURE")),
                new PinnedPredicate(UUID.randomUUID(), new StringListIncludesPredicate("WEDDINGS"))));
        assertThat(signal).isInstanceOf(SignalBlock.class);
        assertThat(signal).isNotInstanceOf(SingleOutputSignal.class);
    }

    @Test
    void compoundSignalRejectsFewerThanTwoSubSignals() {
        Signal onlyOne = new Signal(UUID.randomUUID(), new Self(), null, new IsTruePredicate());
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CompoundSignal(UUID.randomUUID(), Combinator.ALL, List.of(onlyOne)));
    }

    @Test
    void compoundSignalCombinesDistinctSubSignalsInOrder() {
        Signal hireDate = new Signal(UUID.randomUUID(), new Self(), null, new IsTruePredicate());
        Signal firstName = new Signal(UUID.randomUUID(), new Self(), null, new StringExistsPredicate());

        CompoundSignal compound = new CompoundSignal(UUID.randomUUID(), Combinator.ALL, List.of(hireDate, firstName));

        assertThat(compound.subSignals()).containsExactly(hireDate, firstName);
    }

    @Test
    void countedSignalRejectsAContextWithNoEachStep() {
        Context notIterated = new StepToLink(UUID.randomUUID(), false, new Self());
        Signal wrapped = new Signal(UUID.randomUUID(), new Self(), null, new IsTruePredicate());

        assertThatIllegalArgumentException().isThrownBy(() ->
                new CountedSignal(UUID.randomUUID(), notIterated, wrapped,
                        new NumberPredicate(ComparisonOperator.GREATER_THAN, 0, null)));
    }

    @Test
    void countedSignalAcceptsAContextWithAnEachStepSomewhereInTheChain() {
        // each is on the second hop, not the first -- containsEachStep must recurse, not just check
        // the outermost step.
        Context photosThenEachPortfolio = new StepToItem(UUID.randomUUID(), false,
                new StepToItem(UUID.randomUUID(), true, new Self()));
        Signal approved = new Signal(UUID.randomUUID(), new Self(), null, new StateEqualsPredicate(UUID.randomUUID()));

        CountedSignal counted = new CountedSignal(UUID.randomUUID(), photosThenEachPortfolio, approved,
                new NumberPredicate(ComparisonOperator.GREATER_THAN, 0, null));

        assertThat(counted.subSignal()).isEqualTo(approved);
    }

    @Test
    void countedSignalCanWrapACompoundSignal() {
        // The HAS ACTIVE PORTFOLIO PHOTO shape: counted signal wrapping a compound signal, nesting
        // arbitrarily deep -- see the worked example in "Marker actions" / "Signals: reading facts".
        Context eachPortfolio = new StepToItem(UUID.randomUUID(), true, new Self());
        Signal createDate = new Signal(UUID.randomUUID(), new Self(), null,
                new DatePredicate(DateComparisonOperator.AFTER, java.time.Instant.EPOCH, null));
        Signal approvedStatus = new Signal(UUID.randomUUID(), new Self(), null, new StateEqualsPredicate(UUID.randomUUID()));
        CompoundSignal both = new CompoundSignal(UUID.randomUUID(), Combinator.ALL, List.of(createDate, approvedStatus));

        CountedSignal counted = new CountedSignal(UUID.randomUUID(), eachPortfolio, both,
                new NumberPredicate(ComparisonOperator.GREATER_THAN, 0, null));

        assertThat(counted.subSignal()).isInstanceOf(CompoundSignal.class);
    }

    @Test
    void everySingleOutputSignalExposesItsOwnPinId() {
        UUID signalId = UUID.randomUUID();
        UUID compoundId = UUID.randomUUID();
        UUID countedId = UUID.randomUUID();

        SingleOutputSignal signal = new Signal(signalId, new Self(), null, new IsTruePredicate());
        SingleOutputSignal compound = new CompoundSignal(compoundId, Combinator.ANY, List.of(
                new Signal(UUID.randomUUID(), new Self(), null, new IsTruePredicate()),
                new Signal(UUID.randomUUID(), new Self(), null, new IsFalsePredicate())));
        SingleOutputSignal counted = new CountedSignal(countedId,
                new StepToItem(UUID.randomUUID(), true, new Self()),
                new Signal(UUID.randomUUID(), new Self(), null, new IsTruePredicate()),
                new NumberPredicate(ComparisonOperator.GREATER_THAN, 0, null));

        assertThat(signal.id()).isEqualTo(signalId);
        assertThat(compound.id()).isEqualTo(compoundId);
        assertThat(counted.id()).isEqualTo(countedId);
    }
}

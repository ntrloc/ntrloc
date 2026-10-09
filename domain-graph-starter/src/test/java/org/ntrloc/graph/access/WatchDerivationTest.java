package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Pins down the watch-derivation rule worked through in "Pipeline-level watches": the atomic unit is
// each individually-referenced thing (a specific property/link/state machine id) anywhere in any
// signal's context, not the chain as a whole and not `each`.
class WatchDerivationTest {

    private static Pipeline pipelineOf(SignalBlock... signals) {
        return new Pipeline(UUID.randomUUID(), List.of(signals), List.of(),
                new Start(UUID.randomUUID()), new End(UUID.randomUUID()), List.of());
    }

    @Test
    void aFlatSignalWatchesJustItsOwnReferencedThing() {
        UUID firstName = UUID.randomUUID();
        Signal signal = new Signal(UUID.randomUUID(),
                new StepToProperty(firstName, false, new Self()), null, new StringExistsPredicate());

        Set<UUID> watches = WatchDerivation.derive(pipelineOf(signal));

        assertThat(watches).containsExactly(firstName);
    }

    @Test
    void selfContextWithNoStepsWatchesNothing() {
        Signal signal = new Signal(UUID.randomUUID(), new Self(), "firstName", new StringExistsPredicate());
        assertThat(WatchDerivation.derive(pipelineOf(signal))).isEmpty();
    }

    @Test
    void aMultiHopChainDecomposesIntoOneWatchPerStepNotOneForTheWholeChain() {
        // photos.portfolios.approvedStatus -- the exact case walked through in the design notes:
        // three separate, independently watch-worthy things, not one combined watch for the chain.
        UUID photos = UUID.randomUUID();
        UUID portfolios = UUID.randomUUID();
        UUID approvedStatus = UUID.randomUUID();

        Context chain = new StepToItem(photos, true,
                new StepToItem(portfolios, true,
                        new StepToStateMachine(approvedStatus)));
        CountedSignal signal = new CountedSignal(UUID.randomUUID(), chain,
                new Signal(UUID.randomUUID(), new Self(), null, new StateEqualsPredicate(UUID.randomUUID())),
                new NumberPredicate(ComparisonOperator.GREATER_THAN, 0, null));

        Set<UUID> watches = WatchDerivation.derive(pipelineOf(signal));

        assertThat(watches).containsExactlyInAnyOrder(photos, portfolios, approvedStatus);
    }

    @Test
    void eachFlagDoesNotAffectWatchIdentity() {
        // Two signals referencing the same link, one iterated and one flattened, still produce one
        // watch -- each is about consumption within a chain, not about what's being watched.
        UUID photosLink = UUID.randomUUID();
        Signal iterated = new Signal(UUID.randomUUID(),
                new StepToItem(photosLink, true, new Self()), null, new IsTruePredicate());
        Signal flattened = new Signal(UUID.randomUUID(),
                new StepToItem(photosLink, false, new Self()), null, new IsTruePredicate());

        Set<UUID> watches = WatchDerivation.derive(pipelineOf(iterated, flattened));

        assertThat(watches).containsExactly(photosLink);
    }

    @Test
    void compoundSignalContributesNoContextOfItsOwnButItsSubSignalsDo() {
        UUID hireDate = UUID.randomUUID();
        UUID firstName = UUID.randomUUID();
        Signal a = new Signal(UUID.randomUUID(), new StepToProperty(hireDate, false, new Self()), null, new IsTruePredicate());
        Signal b = new Signal(UUID.randomUUID(), new StepToProperty(firstName, false, new Self()), null, new StringExistsPredicate());
        CompoundSignal compound = new CompoundSignal(UUID.randomUUID(), Combinator.ALL, List.of(a, b));

        Set<UUID> watches = WatchDerivation.derive(pipelineOf(compound));

        assertThat(watches).containsExactlyInAnyOrder(hireDate, firstName);
    }

    @Test
    void predicateSignalsContextIsWatchedLikeAnyOtherSignal() {
        UUID genres = UUID.randomUUID();
        PredicateSignal predicateSignal = new PredicateSignal(
                new StepToProperty(genres, false, new Self()), "genres", List.of(
                new PinnedPredicate(UUID.randomUUID(), new StringListIncludesPredicate("NATURE")),
                new PinnedPredicate(UUID.randomUUID(), new StringListIncludesPredicate("WEDDINGS"))));

        Set<UUID> watches = WatchDerivation.derive(pipelineOf(predicateSignal));

        assertThat(watches).containsExactly(genres);
    }

    // A pipeline with no signals at all (e.g. a bare <set> with no gate) can never be triggered by
    // a fact change, since it references no fact -- but it still needs exactly one trigger to ever
    // run. ITEM_CREATED is that trigger; see WatchDerivation's own comment on the sentinel. This is
    // deliberately distinct from selfContextWithNoStepsWatchesNothing above: that pipeline DOES have
    // a real, admin-authored gate (a bare Self context that just doesn't resolve to a watchable id),
    // and must stay watching nothing rather than being mistaken for unconditional.
    @Test
    void aPipelineWithNoSignalsWatchesOnlyItemCreated() {
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(), List.of(),
                new Start(UUID.randomUUID()), new End(UUID.randomUUID()), List.of());
        assertThat(WatchDerivation.derive(pipeline)).containsExactly(WatchDerivation.ITEM_CREATED);
    }
}

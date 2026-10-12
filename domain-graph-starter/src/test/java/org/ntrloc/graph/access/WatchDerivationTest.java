package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Pins down the watch-derivation rule worked through in "Pipeline-level watches": the atomic unit is
// each individually-referenced thing (a specific property/link/state machine id) anywhere in any
// condition's subject chain, not the chain as a whole and not `each`.
class WatchDerivationTest {

    private static Pipeline pipelineOf(SignalBlock... signals) {
        return new Pipeline(UUID.randomUUID(), List.of(signals), List.of(),
                new Start(UUID.randomUUID()), new End(UUID.randomUUID()), List.of());
    }

    @Test
    void aFlatConditionWatchesJustItsOwnReferencedThing() {
        UUID firstName = UUID.randomUUID();
        Condition condition = new Condition(UUID.randomUUID(),
                new StepToProperty(firstName, false, new Self()), new StringExistsPredicate(null));

        Set<UUID> watches = WatchDerivation.derive(pipelineOf(condition));

        assertThat(watches).containsExactly(firstName);
    }

    @Test
    void selfSubjectWithNoStepsWatchesNothing() {
        Condition condition = new Condition(UUID.randomUUID(), new Self(), new StringExistsPredicate(null));
        assertThat(WatchDerivation.derive(pipelineOf(condition))).isEmpty();
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
        CountedPredicate counted = new CountedPredicate(chain,
                new StateEqualsPredicate(null, UUID.randomUUID()),
                new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 0, null));
        Condition condition = new Condition(UUID.randomUUID(), null, counted);

        Set<UUID> watches = WatchDerivation.derive(pipelineOf(condition));

        assertThat(watches).containsExactlyInAnyOrder(photos, portfolios, approvedStatus);
    }

    @Test
    void eachFlagDoesNotAffectWatchIdentity() {
        // Two conditions referencing the same link, one iterated and one flattened, still produce one
        // watch -- each is about consumption within a chain, not about what's being watched.
        UUID photosLink = UUID.randomUUID();
        Condition iterated = new Condition(UUID.randomUUID(),
                new StepToItem(photosLink, true, new Self()), new IsTruePredicate(null));
        Condition flattened = new Condition(UUID.randomUUID(),
                new StepToItem(photosLink, false, new Self()), new IsTruePredicate(null));

        Set<UUID> watches = WatchDerivation.derive(pipelineOf(iterated, flattened));

        assertThat(watches).containsExactly(photosLink);
    }

    @Test
    void totalityPredicateContributesNoSubjectOfItsOwnByDefaultButItsSubPredicatesDo() {
        UUID hireDate = UUID.randomUUID();
        UUID firstName = UUID.randomUUID();
        Predicate a = new IsTruePredicate(new StepToProperty(hireDate, false, new Self()));
        Predicate b = new StringExistsPredicate(new StepToProperty(firstName, false, new Self()));
        TotalityPredicate totality = new TotalityPredicate(null, Quantity.ALL, List.of(a, b));
        Condition condition = new Condition(UUID.randomUUID(), null, totality);

        Set<UUID> watches = WatchDerivation.derive(pipelineOf(condition));

        assertThat(watches).containsExactlyInAnyOrder(hireDate, firstName);
    }

    @Test
    void conditionGroupsSubjectIsWatchedLikeAnyOtherCondition() {
        UUID genres = UUID.randomUUID();
        Condition nature = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "NATURE"));
        Condition weddings = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "WEDDINGS"));
        ConditionGroup group = new ConditionGroup(new StepToProperty(genres, false, new Self()), List.of(nature, weddings));

        Set<UUID> watches = WatchDerivation.derive(pipelineOf(group));

        assertThat(watches).containsExactly(genres);
    }

    @Test
    void aSelectsReferencedConditionsAreWatched_noSelectAwarenessNeededInWatchDerivation() {
        // Select never embeds a private signal graph -- every condition it references already lives in
        // topLevelSignals like any other, so WatchDerivation needs zero Select-specific handling to
        // correctly watch what a <select>-based pipeline actually depends on.
        UUID genres = UUID.randomUUID();
        Condition nature = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "NATURE"));
        Condition weddings = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "WEDDINGS"));
        ConditionGroup group = new ConditionGroup(new StepToProperty(genres, false, new Self()), List.of(nature, weddings));

        AddMarkers natureRow = new AddMarkers(UUID.randomUUID(), Gate.of(nature.id()), List.of(UUID.randomUUID()));
        AddMarkers weddingsRow = new AddMarkers(UUID.randomUUID(), Gate.of(weddings.id()), List.of(UUID.randomUUID()));
        Select select = new Select(UUID.randomUUID(), new Gate(List.of(nature.id(), weddings.id()), Quantity.NONE),
                List.of(natureRow, weddingsRow));

        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(group), List.of(select),
                new Start(UUID.randomUUID()), new End(UUID.randomUUID()), List.of());

        Set<UUID> watches = WatchDerivation.derive(pipeline);

        assertThat(watches).containsExactly(genres);
    }

    // A pipeline with no signals at all (e.g. a bare <set> with no gate) can never be triggered by
    // a fact change, since it references no fact -- but it still needs exactly one trigger to ever
    // run. ITEM_CREATED is that trigger; see WatchDerivation's own comment on the sentinel. This is
    // deliberately distinct from selfSubjectWithNoStepsWatchesNothing above: that pipeline DOES have
    // a real, admin-authored gate (a bare Self subject that just doesn't resolve to a watchable id),
    // and must stay watching nothing rather than being mistaken for unconditional.
    @Test
    void aPipelineWithNoSignalsWatchesOnlyItemCreated() {
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(), List.of(),
                new Start(UUID.randomUUID()), new End(UUID.randomUUID()), List.of());
        assertThat(WatchDerivation.derive(pipeline)).containsExactly(WatchDerivation.ITEM_CREATED);
    }
}

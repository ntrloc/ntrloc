package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// First evaluator slice: an item's markers driven by a property change on the item itself -- the
// same scenario the retired RuleAppliedMarker mechanism covered, now via the pipeline's own
// Context/Predicate/carrier model instead of a DMN decision. Each test builds its own throwaway
// Pipeline; selfId is a plain random UUID throughout since none of these scenarios touch
// IdentityPredicate.
class PipelineEvaluatorTest {

    private final UUID propertyId = UUID.randomUUID();
    private final UUID markerId = UUID.randomUUID();
    private final UUID selfId = UUID.randomUUID();

    private Condition propertyEquals(String value) {
        return new Condition(UUID.randomUUID(), new StepToProperty(propertyId, false, new Self()),
                new StringEqualsPredicate(null, value));
    }

    private Pipeline singleGatedCarrier(Condition gateCondition, CarrierBlock carrier) {
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        return new Pipeline(UUID.randomUUID(), List.of(gateCondition), List.of(carrier), start, end, List.of(
                new Route(start.id(), List.of(carrier.id())),
                new Route(carrier.id(), List.of(end.id()))));
    }

    @Test
    void addMarkers_firesWhenItsOwnPropertyMatchesTheGate() {
        Condition gate = propertyEquals("OUT_OF_STOCK");
        AddMarkers add = new AddMarkers(UUID.randomUUID(), Gate.of(gate.id()), List.of(markerId));
        Pipeline pipeline = singleGatedCarrier(gate, add);

        Set<UUID> result = PipelineEvaluator.evaluate(pipeline, Map.of(propertyId, "OUT_OF_STOCK"), selfId);

        assertThat(result).containsExactly(markerId);
    }

    @Test
    void addMarkers_doesNotFireWhenItsOwnPropertyDoesNotMatchTheGate() {
        Condition gate = propertyEquals("OUT_OF_STOCK");
        AddMarkers add = new AddMarkers(UUID.randomUUID(), Gate.of(gate.id()), List.of(markerId));
        Pipeline pipeline = singleGatedCarrier(gate, add);

        Set<UUID> result = PipelineEvaluator.evaluate(pipeline, Map.of(propertyId, "IN_STOCK"), selfId);

        assertThat(result).isEmpty();
    }

    @Test
    void removeMarkers_dropsTheMarkerWhenItsGateFires() {
        Condition gate = propertyEquals("IN_STOCK");
        UUID otherMarker = UUID.randomUUID();
        AddMarkers seed = new AddMarkers(UUID.randomUUID(), null, List.of(markerId, otherMarker));
        RemoveMarkers remove = new RemoveMarkers(UUID.randomUUID(), Gate.of(gate.id()), List.of(markerId));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(gate), List.of(seed, remove), start, end, List.of(
                new Route(start.id(), List.of(seed.id())),
                new Route(seed.id(), List.of(remove.id())),
                new Route(remove.id(), List.of(end.id()))));

        Set<UUID> result = PipelineEvaluator.evaluate(pipeline, Map.of(propertyId, "IN_STOCK"), selfId);

        assertThat(result).containsExactly(otherMarker);
    }

    @Test
    void setMarkers_replacesWhateverCarrierItReceivesWhenItsGateFires() {
        Condition gate = propertyEquals("DISCONTINUED");
        UUID discontinuedMarker = UUID.randomUUID();
        AddMarkers seed = new AddMarkers(UUID.randomUUID(), null, List.of(markerId));
        SetMarkers set = new SetMarkers(UUID.randomUUID(), Gate.of(gate.id()), List.of(discontinuedMarker));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(gate), List.of(seed, set), start, end, List.of(
                new Route(start.id(), List.of(seed.id())),
                new Route(seed.id(), List.of(set.id())),
                new Route(set.id(), List.of(end.id()))));

        Set<UUID> result = PipelineEvaluator.evaluate(pipeline, Map.of(propertyId, "DISCONTINUED"), selfId);

        assertThat(result).containsExactly(discontinuedMarker);
    }

    @Test
    void anUnwiredCarrier_alwaysFiresRegardlessOfAnyPropertyValue() {
        AddMarkers add = new AddMarkers(UUID.randomUUID(), null, List.of(markerId));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(), List.of(add), start, end, List.of(
                new Route(start.id(), List.of(add.id())),
                new Route(add.id(), List.of(end.id()))));

        Set<UUID> result = PipelineEvaluator.evaluate(pipeline, Map.of(), selfId);

        assertThat(result).containsExactly(markerId);
    }

    @Test
    void filter_passesItsCarrierThroughUnchangedWhenItsSignalIsOn() {
        Condition gate = propertyEquals("IN_STOCK");
        AddMarkers seed = new AddMarkers(UUID.randomUUID(), null, List.of(markerId));
        Filter filter = new Filter(UUID.randomUUID(), Gate.of(gate.id()));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(gate), List.of(seed, filter), start, end, List.of(
                new Route(start.id(), List.of(seed.id())),
                new Route(seed.id(), List.of(filter.id())),
                new Route(filter.id(), List.of(end.id()))));

        Set<UUID> result = PipelineEvaluator.evaluate(pipeline, Map.of(propertyId, "IN_STOCK"), selfId);

        assertThat(result).containsExactly(markerId);
    }

    @Test
    void filter_producesAnEmptyCarrierWhenItsSignalIsOff() {
        Condition gate = propertyEquals("IN_STOCK");
        AddMarkers seed = new AddMarkers(UUID.randomUUID(), null, List.of(markerId));
        Filter filter = new Filter(UUID.randomUUID(), Gate.of(gate.id()));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(gate), List.of(seed, filter), start, end, List.of(
                new Route(start.id(), List.of(seed.id())),
                new Route(seed.id(), List.of(filter.id())),
                new Route(filter.id(), List.of(end.id()))));

        Set<UUID> result = PipelineEvaluator.evaluate(pipeline, Map.of(propertyId, "OUT_OF_STOCK"), selfId);

        assertThat(result).isEmpty();
    }

    @Test
    void endMerges_everyCarrierThatArrivesFromDistinctBranchesByUnion() {
        // Fan-out from Start into two independent AddMarkers, each unconditional, both routed
        // straight to End -- End's own comment says it's the union of every carrier that ever
        // arrives, not last-write-wins.
        UUID markerA = UUID.randomUUID();
        UUID markerB = UUID.randomUUID();
        AddMarkers addA = new AddMarkers(UUID.randomUUID(), null, List.of(markerA));
        AddMarkers addB = new AddMarkers(UUID.randomUUID(), null, List.of(markerB));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(), List.of(addA, addB), start, end, List.of(
                new Route(start.id(), List.of(addA.id(), addB.id())),
                new Route(addA.id(), List.of(end.id())),
                new Route(addB.id(), List.of(end.id()))));

        Set<UUID> result = PipelineEvaluator.evaluate(pipeline, Map.of(), selfId);

        assertThat(result).containsExactlyInAnyOrder(markerA, markerB);
    }

    @Test
    void aBlockFedByTwoSources_mergesThemByUnionBeforeItsOwnTransform() {
        // Fan-in: two independent AddMarkers feed one shared RemoveMarkers -- the merge happens
        // before RemoveMarkers' own transform runs, per Route's "fan-in is implicit carrier merge".
        UUID markerA = UUID.randomUUID();
        UUID markerB = UUID.randomUUID();
        AddMarkers addA = new AddMarkers(UUID.randomUUID(), null, List.of(markerA));
        AddMarkers addB = new AddMarkers(UUID.randomUUID(), null, List.of(markerB));
        RemoveMarkers removeA = new RemoveMarkers(UUID.randomUUID(), null, List.of(markerA));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(), List.of(addA, addB, removeA), start, end, List.of(
                new Route(start.id(), List.of(addA.id(), addB.id())),
                new Route(addA.id(), List.of(removeA.id())),
                new Route(addB.id(), List.of(removeA.id())),
                new Route(removeA.id(), List.of(end.id()))));

        Set<UUID> result = PipelineEvaluator.evaluate(pipeline, Map.of(), selfId);

        assertThat(result).containsExactly(markerB);
    }

    @Test
    void totalityPredicateAll_gatesOnlyWhenEveryPropertyConditionHolds() {
        UUID otherPropertyId = UUID.randomUUID();
        Predicate inStock = new StringEqualsPredicate(new StepToProperty(propertyId, false, new Self()), "IN_STOCK");
        Predicate featured = new IsTruePredicate(new StepToProperty(otherPropertyId, false, new Self()));
        Condition both = new Condition(UUID.randomUUID(), null, new TotalityPredicate(null, Quantity.ALL, List.of(inStock, featured)));
        AddMarkers add = new AddMarkers(UUID.randomUUID(), Gate.of(both.id()), List.of(markerId));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(both), List.of(add), start, end, List.of(
                new Route(start.id(), List.of(add.id())),
                new Route(add.id(), List.of(end.id()))));

        Map<UUID, Object> bothTrue = Map.of(propertyId, "IN_STOCK", otherPropertyId, true);
        Map<UUID, Object> onlyOneTrue = Map.of(propertyId, "IN_STOCK", otherPropertyId, false);

        assertThat(PipelineEvaluator.evaluate(pipeline, bothTrue, selfId)).containsExactly(markerId);
        assertThat(PipelineEvaluator.evaluate(pipeline, onlyOneTrue, selfId)).isEmpty();
    }

    @Test
    void select_rowFiresAndAddsItsMarkerIndependently() {
        UUID genrePropertyId = UUID.randomUUID();
        UUID natureMarker = UUID.randomUUID();
        UUID weddingsMarker = UUID.randomUUID();

        Condition isNature = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "NATURE"));
        Condition isWeddings = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "WEDDINGS"));
        ConditionGroup genres = new ConditionGroup(new StepToProperty(genrePropertyId, false, new Self()), List.of(isNature, isWeddings));

        AddMarkers natureRow = new AddMarkers(UUID.randomUUID(), Gate.of(isNature.id()), List.of(natureMarker));
        AddMarkers weddingsRow = new AddMarkers(UUID.randomUUID(), Gate.of(isWeddings.id()), List.of(weddingsMarker));
        Select select = new Select(UUID.randomUUID(), new Gate(List.of(isNature.id(), isWeddings.id()), Quantity.NONE),
                List.of(natureRow, weddingsRow));

        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        // Only the nature row's own output is routed anywhere -- isolating that each row is its own
        // independently addressable carrier pin, not just reachable via the block's primary one.
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(genres), List.of(select), start, end, List.of(
                new Route(start.id(), List.of(select.id())),
                new Route(natureRow.id(), List.of(end.id()))));

        Set<UUID> result = PipelineEvaluator.evaluate(pipeline, Map.of(genrePropertyId, List.of("NATURE")), selfId);

        assertThat(result).containsExactly(natureMarker);
    }

    @Test
    void select_primaryPinIsOtherwise_emptyWhenARowMatched_passthroughWhenNoneDid() {
        UUID genrePropertyId = UUID.randomUUID();
        UUID baselineMarker = UUID.randomUUID();

        Condition isNature = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "NATURE"));
        Condition isWeddings = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "WEDDINGS"));
        ConditionGroup genres = new ConditionGroup(new StepToProperty(genrePropertyId, false, new Self()), List.of(isNature, isWeddings));

        AddMarkers natureRow = new AddMarkers(UUID.randomUUID(), Gate.of(isNature.id()), List.of(UUID.randomUUID()));
        AddMarkers weddingsRow = new AddMarkers(UUID.randomUUID(), Gate.of(isWeddings.id()), List.of(UUID.randomUUID()));
        // Authored the way an admin naturally would: NONE over the same two conditions the rows
        // themselves gate on -- nothing structurally special, just an ordinary Gate (see Select's own
        // comment on why this isn't enforced as a relationship, just a convention).
        Select select = new Select(UUID.randomUUID(), new Gate(List.of(isNature.id(), isWeddings.id()), Quantity.NONE),
                List.of(natureRow, weddingsRow));

        AddMarkers seed = new AddMarkers(UUID.randomUUID(), null, List.of(baselineMarker));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        // Only Select's own primary pin routes to End here -- the rows' own outputs are deliberately
        // unrouted, isolating exactly what (Otherwise) alone carries.
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(genres), List.of(seed, select), start, end, List.of(
                new Route(start.id(), List.of(seed.id())),
                new Route(seed.id(), List.of(select.id())),
                new Route(select.id(), List.of(end.id()))));

        Set<UUID> matched = PipelineEvaluator.evaluate(pipeline, Map.of(genrePropertyId, List.of("NATURE")), selfId);
        Set<UUID> unmatched = PipelineEvaluator.evaluate(pipeline, Map.of(genrePropertyId, List.of("PORTRAIT")), selfId);

        assertThat(matched).isEmpty();
        assertThat(unmatched).containsExactly(baselineMarker);
    }

    @Test
    void select_rowsEachFeedTheirOwnFilterGatedByASharedCondition_evaluatedOnceReusedTwice() {
        // Mirrors the actual Photographer pipeline shape: <select> on genres routes whichever marker
        // matched through its own <filter>, both filters sharing one condition, pulled once and reused
        // -- see SignalEvaluator's own "a condition runs at most once per evaluation" comment. The
        // shared condition here is a plain property check, standing in for "has an approved portfolio
        // photo" (that one needs CountedPredicate, not yet evaluatable -- see SignalEvaluator's own
        // UnsupportedOperationException) -- this isolates the wiring/fan-out-reuse behavior instead.
        UUID genrePropertyId = UUID.randomUUID();
        UUID approvedPropertyId = UUID.randomUUID();
        UUID natureMarker = UUID.randomUUID();
        UUID weddingsMarker = UUID.randomUUID();

        Condition isNature = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "NATURE"));
        Condition isWeddings = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "WEDDINGS"));
        ConditionGroup genres = new ConditionGroup(new StepToProperty(genrePropertyId, false, new Self()), List.of(isNature, isWeddings));
        Condition approved = new Condition(UUID.randomUUID(), new StepToProperty(approvedPropertyId, false, new Self()), new IsTruePredicate(null));

        AddMarkers natureRow = new AddMarkers(UUID.randomUUID(), Gate.of(isNature.id()), List.of(natureMarker));
        AddMarkers weddingsRow = new AddMarkers(UUID.randomUUID(), Gate.of(isWeddings.id()), List.of(weddingsMarker));
        Select select = new Select(UUID.randomUUID(), new Gate(List.of(isNature.id(), isWeddings.id()), Quantity.NONE),
                List.of(natureRow, weddingsRow));
        Filter natureFilter = new Filter(UUID.randomUUID(), Gate.of(approved.id()));
        Filter weddingsFilter = new Filter(UUID.randomUUID(), Gate.of(approved.id()));

        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(genres, approved), List.of(select, natureFilter, weddingsFilter),
                start, end, List.of(
                new Route(start.id(), List.of(select.id())),
                new Route(natureRow.id(), List.of(natureFilter.id())),
                new Route(weddingsRow.id(), List.of(weddingsFilter.id())),
                new Route(natureFilter.id(), List.of(end.id())),
                new Route(weddingsFilter.id(), List.of(end.id()))));

        Map<UUID, Object> natureApproved = Map.of(genrePropertyId, List.of("NATURE"), approvedPropertyId, true);
        Map<UUID, Object> natureNotApproved = Map.of(genrePropertyId, List.of("NATURE"), approvedPropertyId, false);
        Map<UUID, Object> neitherGenre = Map.of(genrePropertyId, List.of("PORTRAIT"), approvedPropertyId, true);

        assertThat(PipelineEvaluator.evaluate(pipeline, natureApproved, selfId)).containsExactly(natureMarker);
        assertThat(PipelineEvaluator.evaluate(pipeline, natureNotApproved, selfId)).isEmpty();
        assertThat(PipelineEvaluator.evaluate(pipeline, neitherGenre, selfId)).isEmpty();
    }
}

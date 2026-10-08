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

    private Signal propertyEquals(String value) {
        return new Signal(UUID.randomUUID(), new StepToProperty(propertyId, false, new Self()),
                null, new StringEqualsPredicate(value));
    }

    private Pipeline singleGatedCarrier(Signal gateSignal, CarrierBlock carrier) {
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        return new Pipeline(UUID.randomUUID(), List.of(gateSignal), List.of(carrier), start, end, List.of(
                new Route(start.id(), List.of(carrier.id())),
                new Route(carrier.id(), List.of(end.id()))));
    }

    @Test
    void addMarkers_firesWhenItsOwnPropertyMatchesTheGate() {
        Signal gate = propertyEquals("OUT_OF_STOCK");
        AddMarkers add = new AddMarkers(UUID.randomUUID(), gate.id(), List.of(markerId));
        Pipeline pipeline = singleGatedCarrier(gate, add);

        Set<UUID> result = PipelineEvaluator.evaluate(pipeline, Map.of(propertyId, "OUT_OF_STOCK"), selfId);

        assertThat(result).containsExactly(markerId);
    }

    @Test
    void addMarkers_doesNotFireWhenItsOwnPropertyDoesNotMatchTheGate() {
        Signal gate = propertyEquals("OUT_OF_STOCK");
        AddMarkers add = new AddMarkers(UUID.randomUUID(), gate.id(), List.of(markerId));
        Pipeline pipeline = singleGatedCarrier(gate, add);

        Set<UUID> result = PipelineEvaluator.evaluate(pipeline, Map.of(propertyId, "IN_STOCK"), selfId);

        assertThat(result).isEmpty();
    }

    @Test
    void removeMarkers_dropsTheMarkerWhenItsGateFires() {
        Signal gate = propertyEquals("IN_STOCK");
        UUID otherMarker = UUID.randomUUID();
        AddMarkers seed = new AddMarkers(UUID.randomUUID(), null, List.of(markerId, otherMarker));
        RemoveMarkers remove = new RemoveMarkers(UUID.randomUUID(), gate.id(), List.of(markerId));
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
        Signal gate = propertyEquals("DISCONTINUED");
        UUID discontinuedMarker = UUID.randomUUID();
        AddMarkers seed = new AddMarkers(UUID.randomUUID(), null, List.of(markerId));
        SetMarkers set = new SetMarkers(UUID.randomUUID(), gate.id(), List.of(discontinuedMarker));
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
        Signal gate = propertyEquals("IN_STOCK");
        AddMarkers seed = new AddMarkers(UUID.randomUUID(), null, List.of(markerId));
        Filter filter = new Filter(UUID.randomUUID(), gate.id());
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
        Signal gate = propertyEquals("IN_STOCK");
        AddMarkers seed = new AddMarkers(UUID.randomUUID(), null, List.of(markerId));
        Filter filter = new Filter(UUID.randomUUID(), gate.id());
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
    void compoundSignalAll_gatesOnlyWhenEveryPropertyConditionHolds() {
        UUID otherPropertyId = UUID.randomUUID();
        Signal inStock = propertyEquals("IN_STOCK");
        Signal featured = new Signal(UUID.randomUUID(), new StepToProperty(otherPropertyId, false, new Self()),
                null, new IsTruePredicate());
        CompoundSignal both = new CompoundSignal(UUID.randomUUID(), Combinator.ALL, List.of(inStock, featured));
        AddMarkers add = new AddMarkers(UUID.randomUUID(), both.id(), List.of(markerId));
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
}

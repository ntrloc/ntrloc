package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

// Pins down Pipeline's two jobs: start/end are structurally singular (dedicated fields, not list
// entries), and every gate/signal reference and every Route id is validated against the actual graph
// at construction time, not discovered later at evaluation time.
class PipelineTest {

    @Test
    void constructsASimpleGatedAddMarkersPipeline() {
        UUID signalId = UUID.randomUUID();
        Signal embargoed = new Signal(signalId, new Self(), null, new IsTruePredicate());

        UUID startId = UUID.randomUUID();
        UUID addId = UUID.randomUUID();
        UUID endId = UUID.randomUUID();
        Start start = new Start(startId);
        AddMarkers add = new AddMarkers(addId, signalId, List.of(UUID.randomUUID()));
        End end = new End(endId);

        Pipeline pipeline = new Pipeline(
                UUID.randomUUID(),
                List.of(embargoed),
                List.of(add),
                start,
                end,
                List.of(new Route(startId, List.of(addId)), new Route(addId, List.of(endId))));

        assertThat(pipeline.carrierBlocks()).containsExactly(add);
    }

    @Test
    void rejectsAGateReferencingAnUnknownSignalPin() {
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        AddMarkers danglingGate = new AddMarkers(UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID()));

        assertThatIllegalArgumentException().isThrownBy(() ->
                new Pipeline(UUID.randomUUID(), List.of(), List.of(danglingGate), start, end, List.of()));
    }

    @Test
    void rejectsARouteReferencingAnUnknownCarrierBlock() {
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());

        assertThatIllegalArgumentException().isThrownBy(() ->
                new Pipeline(UUID.randomUUID(), List.of(), List.of(), start, end,
                        List.of(new Route(start.id(), List.of(UUID.randomUUID())))));
    }

    @Test
    void filterSignalReferenceIsValidatedTheSameWayAsAGate() {
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Filter danglingSignal = new Filter(UUID.randomUUID(), UUID.randomUUID());

        assertThatIllegalArgumentException().isThrownBy(() ->
                new Pipeline(UUID.randomUUID(), List.of(), List.of(danglingSignal), start, end, List.of()));
    }

    @Test
    void collectSignalPinIdsReachesPredicateSignalsPinnedPredicates() {
        PinnedPredicate nature = new PinnedPredicate(UUID.randomUUID(), new StringListIncludesPredicate("NATURE"));
        PinnedPredicate weddings = new PinnedPredicate(UUID.randomUUID(), new StringListIncludesPredicate("WEDDINGS"));
        PredicateSignal genres = new PredicateSignal(new Self(), "genres", List.of(nature, weddings));

        var ids = Pipeline.collectSignalPinIds(List.of(genres));

        assertThat(ids).containsExactlyInAnyOrder(nature.id(), weddings.id());
    }

    @Test
    void collectSignalPinIdsRecursesIntoCompoundAndCountedSignals() {
        Signal createDate = new Signal(UUID.randomUUID(), new Self(), null, new IsTruePredicate());
        Signal approvedStatus = new Signal(UUID.randomUUID(), new Self(), null, new IsTruePredicate());
        CompoundSignal both = new CompoundSignal(UUID.randomUUID(), Combinator.ALL, List.of(createDate, approvedStatus));
        CountedSignal counted = new CountedSignal(UUID.randomUUID(),
                new StepToItem(UUID.randomUUID(), true, new Self()), both,
                new NumberPredicate(ComparisonOperator.GREATER_THAN, 0, null));

        var ids = Pipeline.collectSignalPinIds(List.of(counted));

        assertThat(ids).containsExactlyInAnyOrder(counted.id(), both.id(), createDate.id(), approvedStatus.id());
    }

    @Test
    void allSignalBlocksFlattensTheNestedTreeDepthFirst() {
        Signal leaf = new Signal(UUID.randomUUID(), new Self(), null, new IsTruePredicate());
        CountedSignal counted = new CountedSignal(UUID.randomUUID(),
                new StepToItem(UUID.randomUUID(), true, new Self()), leaf,
                new NumberPredicate(ComparisonOperator.GREATER_THAN, 0, null));

        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(counted), List.of(), start, end, List.of());

        assertThat(pipeline.allSignalBlocks()).containsExactly(counted, leaf);
    }
}

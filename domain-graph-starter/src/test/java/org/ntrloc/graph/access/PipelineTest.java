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
        UUID conditionId = UUID.randomUUID();
        Condition embargoed = new Condition(conditionId, new Self(), new IsTruePredicate(null));

        UUID startId = UUID.randomUUID();
        UUID addId = UUID.randomUUID();
        UUID endId = UUID.randomUUID();
        Start start = new Start(startId);
        AddMarkers add = new AddMarkers(addId, Gate.of(conditionId), List.of(UUID.randomUUID()));
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
        AddMarkers danglingGate = new AddMarkers(UUID.randomUUID(), Gate.of(UUID.randomUUID()), List.of(UUID.randomUUID()));

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
        Filter danglingSignal = new Filter(UUID.randomUUID(), Gate.of(UUID.randomUUID()));

        assertThatIllegalArgumentException().isThrownBy(() ->
                new Pipeline(UUID.randomUUID(), List.of(), List.of(danglingSignal), start, end, List.of()));
    }

    @Test
    void rejectsASelectRowGateReferencingAnUnknownSignalPin() {
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        AddMarkers danglingRow = new AddMarkers(UUID.randomUUID(), Gate.of(UUID.randomUUID()), List.of(UUID.randomUUID()));
        Select select = new Select(UUID.randomUUID(), null, List.of(danglingRow));

        assertThatIllegalArgumentException().isThrownBy(() ->
                new Pipeline(UUID.randomUUID(), List.of(), List.of(select), start, end, List.of()));
    }

    @Test
    void aSelectsRowIdsAreValidRouteTargetsInTheirOwnRight() {
        // Nothing routes to a row from outside (see Select's own comment -- it's fed internally), but
        // something downstream absolutely can route *from* one, same as any other carrier block's id.
        Condition isNature = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "NATURE"));
        AddMarkers natureRow = new AddMarkers(UUID.randomUUID(), Gate.of(isNature.id()), List.of(UUID.randomUUID()));
        Select select = new Select(UUID.randomUUID(), null, List.of(natureRow));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());

        Pipeline pipeline = new Pipeline(UUID.randomUUID(), List.of(isNature), List.of(select), start, end, List.of(
                new Route(start.id(), List.of(select.id())),
                new Route(natureRow.id(), List.of(end.id()))));

        assertThat(pipeline.carrierBlocks()).containsExactly(select);
    }

    @Test
    void collectSignalPinIdsReachesEveryConditionInsideAConditionGroup() {
        Condition nature = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "NATURE"));
        Condition weddings = new Condition(UUID.randomUUID(), null, new StringListIncludesPredicate(null, "WEDDINGS"));
        ConditionGroup genres = new ConditionGroup(new Self(), List.of(nature, weddings));

        var ids = Pipeline.collectSignalPinIds(List.of(genres));

        assertThat(ids).containsExactlyInAnyOrder(nature.id(), weddings.id());
    }

    @Test
    void collectSignalPinIdsNeverDescendsIntoAConditionsOwnPredicateTree() {
        // A predicate -- even a TotalityPredicate/CountedPredicate nesting others -- never has a pin
        // of its own; only the enclosing Condition does. So a condition built from a CountedPredicate
        // wrapping a TotalityPredicate wrapping two leaves still contributes exactly one pin: its own.
        Predicate createDate = new IsTruePredicate(null);
        Predicate approvedStatus = new IsTruePredicate(null);
        TotalityPredicate both = new TotalityPredicate(null, Quantity.ALL, List.of(createDate, approvedStatus));
        CountedPredicate counted = new CountedPredicate(
                new StepToItem(UUID.randomUUID(), true, new Self()), both,
                new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 0, null));
        Condition condition = new Condition(UUID.randomUUID(), null, counted);

        var ids = Pipeline.collectSignalPinIds(List.of(condition));

        assertThat(ids).containsExactly(condition.id());
    }
}

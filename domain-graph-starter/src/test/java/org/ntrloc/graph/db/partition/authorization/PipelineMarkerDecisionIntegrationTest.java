package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.access.AddMarkers;
import org.ntrloc.graph.access.End;
import org.ntrloc.graph.access.LinkIsPresentPredicate;
import org.ntrloc.graph.access.Pipeline;
import org.ntrloc.graph.access.Route;
import org.ntrloc.graph.access.Self;
import org.ntrloc.graph.access.Signal;
import org.ntrloc.graph.access.Start;
import org.ntrloc.graph.access.StepToLink;
import org.ntrloc.graph.access.StepToProperty;
import org.ntrloc.graph.access.StringEqualsPredicate;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerDecisionPipelineRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.ManuallyAppliedMarker;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Proves the first evaluator slice (a property change on the item itself driving its own markers)
// is actually wired into LedgerRegisterCoordinatorImpl.prepare(), not just exercised in isolation
// by PipelineEvaluatorTest -- now reading through MarkerDecisionPipelineRepository, the real (only)
// PipelineSource. Every test creates its own throwaway pipeline and deletes it in a finally block,
// same discipline AuthorizationRepositoryMarkerIntegrationTest's rule tests used, since this suite's
// Postgres container and Spring context are shared across the whole run.
class PipelineMarkerDecisionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private MarkerDecisionPipelineRepository pipelineRepository;

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private Pipeline colorEqualsPipeline(String matchValue, UUID markerId) {
        Signal gate = new Signal(UUID.randomUUID(), new StepToProperty(fixture.colorPropertyId(), false, new Self()),
                null, new StringEqualsPredicate(matchValue));
        AddMarkers add = new AddMarkers(UUID.randomUUID(), gate.id(), List.of(markerId));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        return new Pipeline(UUID.randomUUID(), List.of(gate), List.of(add), start, end, List.of(
                new Route(start.id(), List.of(add.id())),
                new Route(add.id(), List.of(end.id()))));
    }

    @Test
    void creatingAnItemWhosePropertyMatchesTheGate_appliesThePipelinesMarker() {
        UUID markerId = authRepo.createMarker("pmd-red-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
        UUID pipelineId = pipelineRepository.createPipeline("red-color", fixture.productTypeId(),
                colorEqualsPipeline("red", markerId)).id();

        try {
            UUID itemId = UUID.randomUUID();
            UUID txn = UUID.randomUUID();
            coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                    Map.of(fixture.colorPropertyId(), "red"), Map.of(), Set.of())), txn, null);
            coordinator.commit(txn, UUID.randomUUID());

            assertThat(authRepo.getMarkerIdsForItem(itemId)).contains(markerId);
        } finally {
            pipelineRepository.deletePipeline(pipelineId);
        }
    }

    @Test
    void creatingAnItemWhosePropertyDoesNotMatchTheGate_appliesNoMarker() {
        UUID markerId = authRepo.createMarker("pmd-red-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
        UUID pipelineId = pipelineRepository.createPipeline("red-color", fixture.productTypeId(),
                colorEqualsPipeline("red", markerId)).id();

        try {
            UUID itemId = UUID.randomUUID();
            UUID txn = UUID.randomUUID();
            coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                    Map.of(fixture.colorPropertyId(), "blue"), Map.of(), Set.of())), txn, null);
            coordinator.commit(txn, UUID.randomUUID());

            assertThat(authRepo.getMarkerIdsForItem(itemId)).doesNotContain(markerId);
        } finally {
            pipelineRepository.deletePipeline(pipelineId);
        }
    }

    @Test
    void updatingAnItemsPropertyToMatchTheGate_addsThePipelinesMarker() {
        UUID markerId = authRepo.createMarker("pmd-red-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
        UUID pipelineId = pipelineRepository.createPipeline("red-color", fixture.productTypeId(),
                colorEqualsPipeline("red", markerId)).id();

        try {
            UUID itemId = UUID.randomUUID();
            UUID createTxn = UUID.randomUUID();
            coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                    Map.of(fixture.colorPropertyId(), "blue"), Map.of(), Set.of())), createTxn, null);
            coordinator.commit(createTxn, UUID.randomUUID());
            assertThat(authRepo.getMarkerIdsForItem(itemId)).doesNotContain(markerId);

            UUID updateTxn = UUID.randomUUID();
            Map<UUID, Object> diff = new HashMap<>();
            diff.put(fixture.colorPropertyId(), "red");
            coordinator.prepare(List.of(new ItemUpdateEntry(itemId, diff, Map.of(), Set.of(), Set.of(), Set.of())), updateTxn, null);
            coordinator.commit(updateTxn, UUID.randomUUID());

            assertThat(authRepo.getMarkerIdsForItem(itemId)).contains(markerId);
        } finally {
            pipelineRepository.deletePipeline(pipelineId);
        }
    }

    @Test
    void updatingAnItemsPropertyAwayFromTheGate_removesTheMarkerThePipelineHadApplied() {
        UUID markerId = authRepo.createMarker("pmd-red-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
        UUID pipelineId = pipelineRepository.createPipeline("red-color", fixture.productTypeId(),
                colorEqualsPipeline("red", markerId)).id();

        try {
            UUID itemId = UUID.randomUUID();
            UUID createTxn = UUID.randomUUID();
            coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                    Map.of(fixture.colorPropertyId(), "red"), Map.of(), Set.of())), createTxn, null);
            coordinator.commit(createTxn, UUID.randomUUID());
            assertThat(authRepo.getMarkerIdsForItem(itemId)).contains(markerId);

            UUID updateTxn = UUID.randomUUID();
            Map<UUID, Object> diff = new HashMap<>();
            diff.put(fixture.colorPropertyId(), "blue");
            coordinator.prepare(List.of(new ItemUpdateEntry(itemId, diff, Map.of(), Set.of(), Set.of(), Set.of())), updateTxn, null);
            coordinator.commit(updateTxn, UUID.randomUUID());

            assertThat(authRepo.getMarkerIdsForItem(itemId)).doesNotContain(markerId);
        } finally {
            pipelineRepository.deletePipeline(pipelineId);
        }
    }

    @Test
    void aManuallyAppliedMarker_survivesAPipelineReEvaluationThatWouldOtherwiseDropIt() {
        UUID markerId = authRepo.createMarker("pmd-red-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
        UUID pipelineId = pipelineRepository.createPipeline("red-color", fixture.productTypeId(),
                colorEqualsPipeline("red", markerId)).id();

        try {
            UUID itemId = UUID.randomUUID();
            UUID createTxn = UUID.randomUUID();
            coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                    Map.of(fixture.colorPropertyId(), "blue"), Map.of(), Set.of())), createTxn, null);
            coordinator.commit(createTxn, UUID.randomUUID());

            UUID manualTxn = UUID.randomUUID();
            coordinator.prepare(List.of(new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(),
                    Set.of(new ManuallyAppliedMarker(markerId, "tester", "pinned by hand")), Set.of())), manualTxn, null);
            coordinator.commit(manualTxn, UUID.randomUUID());
            assertThat(authRepo.getMarkerIdsForItem(itemId)).contains(markerId);

            // This update DOES touch color -- it's a change the pipeline watches, so it genuinely
            // re-evaluates (color is still "blue", still doesn't match "red"). The marker still
            // doesn't survive because the pipeline wants it: replayCurrentPipelineAttribution sees
            // a ManuallyAppliedMarker, not a PipelineAppliedMarker, so there's nothing for this
            // pipeline to remove regardless of what it decides.
            UUID unrelatedTxn = UUID.randomUUID();
            Map<UUID, Object> diff = new HashMap<>();
            diff.put(fixture.colorPropertyId(), "green");
            coordinator.prepare(List.of(new ItemUpdateEntry(itemId, diff, Map.of(), Set.of(), Set.of(), Set.of())), unrelatedTxn, null);
            coordinator.commit(unrelatedTxn, UUID.randomUUID());

            assertThat(authRepo.getMarkerIdsForItem(itemId)).contains(markerId);
        } finally {
            pipelineRepository.deletePipeline(pipelineId);
        }
    }

    // A "tripwire" pipeline: its gate's Context is a StepToLink, which ContextResolver doesn't
    // support yet and throws UnsupportedOperationException for -- so if PipelineMarkerDecisionService
    // ever actually evaluates this pipeline, prepare() throws. WatchDerivation records its watched
    // fact as the link perspective id (not any property), so it's a decisive way to prove "this
    // pipeline was never even invoked" from outside: no exception means it was correctly skipped,
    // not just that it happened to decide nothing.
    private Pipeline tripwirePipeline(UUID markerId) {
        Signal gate = new Signal(UUID.randomUUID(), new StepToLink(fixture.productPerspectiveId(), false, new Self()),
                null, new LinkIsPresentPredicate());
        AddMarkers add = new AddMarkers(UUID.randomUUID(), gate.id(), List.of(markerId));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        return new Pipeline(UUID.randomUUID(), List.of(gate), List.of(add), start, end, List.of(
                new Route(start.id(), List.of(add.id())),
                new Route(add.id(), List.of(end.id()))));
    }

    @Test
    void aPipelineWatchingTheChangedPropertyRuns_whileAPipelineWatchingSomethingElseIsNeverEvaluated() {
        UUID colorMarkerId = authRepo.createMarker("pmd-color-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
        UUID tripwireMarkerId = authRepo.createMarker("pmd-tripwire-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
        UUID colorPipelineId = pipelineRepository.createPipeline("red-color", fixture.productTypeId(),
                colorEqualsPipeline("red", colorMarkerId)).id();
        UUID tripwirePipelineId = pipelineRepository.createPipeline("tripwire", fixture.productTypeId(),
                tripwirePipeline(tripwireMarkerId)).id();

        try {
            UUID itemId = UUID.randomUUID();
            UUID createTxn = UUID.randomUUID();
            // Creating only sets color -- no link is touched. If the tripwire pipeline were
            // (incorrectly) evaluated here just because it's bound to the same item type, this
            // throws and the test fails outright, not just with a wrong assertion.
            coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                    Map.of(fixture.colorPropertyId(), "blue"), Map.of(), Set.of())), createTxn, null);
            coordinator.commit(createTxn, UUID.randomUUID());
            assertThat(authRepo.getMarkerIdsForItem(itemId)).doesNotContain(colorMarkerId, tripwireMarkerId);

            // Updating color -- a fact the color pipeline watches -- runs it and applies its
            // marker, while the tripwire pipeline (watching an unrelated link perspective) is
            // still never evaluated.
            UUID updateTxn = UUID.randomUUID();
            Map<UUID, Object> diff = new HashMap<>();
            diff.put(fixture.colorPropertyId(), "red");
            coordinator.prepare(List.of(new ItemUpdateEntry(itemId, diff, Map.of(), Set.of(), Set.of(), Set.of())), updateTxn, null);
            coordinator.commit(updateTxn, UUID.randomUUID());

            assertThat(authRepo.getMarkerIdsForItem(itemId)).contains(colorMarkerId).doesNotContain(tripwireMarkerId);
        } finally {
            pipelineRepository.deletePipeline(colorPipelineId);
            pipelineRepository.deletePipeline(tripwirePipelineId);
        }
    }

    @Test
    void withNoPipelineRegisteredForTheItemType_mutationsAreUnaffected() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                Map.of(fixture.colorPropertyId(), "red"), Map.of(), Set.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());

        assertThat(authRepo.getMarkerIdsForItem(itemId)).isEmpty();
    }
}

package org.ntrloc.graph.db.partition.register;

import org.flowable.dmn.api.DmnRepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.MarkerAssignmentService;
import org.ntrloc.graph.db.partition.authorization.MarkerDecisionSupport;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.MarkerAttribution;
import org.ntrloc.graph.db.partition.ledger.StateAppliedMarker;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTransitionView;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.ResolvedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Covers state-entry marker decisions (StateMarkerDecisionService, wired into
// LedgerRegisterCoordinatorImpl.prepare after the item-type rule pass): entering a NORMAL state
// with an entryMarkerDecisionKey evaluates that DMN against the item's current properties and
// applies the marker names it returns with StateAppliedMarker provenance; leaving the state (into
// another state or END) revokes them unless the entered state's decision, an item-type rule, or a
// manual application also holds them. Every test builds its own throwaway state machine on the
// shared fixture item type so mutating a state's decision key can't bleed across methods.
class StateMarkerDecisionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EntityManager entityManager;
    @Autowired private LedgerRegisterCoordinator coordinator;
    @Autowired private RegisterPartitionManager registerPartitionManager;
    @Autowired private SchemaManager schemaManager;
    @Autowired private AuthorizationRepository authRepo;
    @Autowired private MarkerAssignmentService markerAssignmentService;
    @Autowired private MarkerDecisionSupport markerDecisionSupport;
    @Autowired private DmnRepositoryService dmnRepositoryService;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private RegisterProjectionTestDomainInitializer fixture;

    private static final NtrlocPrincipal SUPERUSER =
            new ResolvedPrincipal(UUID.randomUUID(), "smd-root", "Root", null, Set.of(), true);

    private static final List<String> MARKER_NAMES = List.of("SmeAlpha", "SmeBravo", "SmeCharlie", "SmeDelta");

    @BeforeEach
    void deployDecisionsAndSeedMarkers() {
        if (dmnRepositoryService.createDecisionQuery().decisionKey("smeMarkersListCell").count() == 0) {
            dmnRepositoryService.createDeployment().name("sme-state-entry-markers")
                    .addClasspathResource("dmn/sme-state-entry-markers.dmn").deploy();
        }
        for (String name : MARKER_NAMES) {
            if (authRepo.findItemTypeScopedMarkerByName(fixture.bookTypeId(), name).isEmpty()) {
                authRepo.createMarker(name, "sme fixture marker", "ITEM_TYPE", fixture.bookTypeId());
            }
        }
    }

    // --- helpers ---

    private UUID createBook(String title) {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.bookTypeId(),
                Map.of(fixture.titlePropertyId(), title), Map.of(), Set.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    // A fresh S1 -> S2 machine (plus the auto START/END pseudostates), with the given entry marker
    // decision keys on S1/S2 (either may be null). Transitions: START->S1 "Begin", S1->S2 "Advance",
    // S2->END "Finish".
    private Machine newMachine(String s1EntryKey, String s2EntryKey) {
        String name = "SmdMachine-" + UUID.randomUUID();
        schemaManager.applyMutations(List.<DefinitionMutation>of(
                new CreateStateMachineMutation(fixture.bookTypeId(), name, null)));
        UUID smId = registerPartitionManager.resolveStateMachineId(fixture.bookTypeId(), name);

        schemaManager.applyMutations(List.<DefinitionMutation>of(
                new CreateStateMutation(smId, "S1", null, null, null, s1EntryKey),
                new CreateStateMutation(smId, "S2", null, null, null, s2EntryKey)));
        UUID s1 = registerPartitionManager.resolveStateId(smId, "S1");
        UUID s2 = registerPartitionManager.resolveStateId(smId, "S2");
        UUID start = registerPartitionManager.resolveStateId(smId, "__start__");
        UUID end = registerPartitionManager.resolveStateId(smId, "__end__");

        schemaManager.applyMutations(List.<DefinitionMutation>of(
                new CreateTransitionMutation(start, s1, "Begin", null, null, null),
                new CreateTransitionMutation(s1, s2, "Advance", null, null, null),
                new CreateTransitionMutation(s2, end, "Finish", null, null, null)));
        return new Machine(name, smId, s1, s2);
    }

    private record Machine(String name, UUID smId, UUID s1, UUID s2) {}

    private UUID transitionId(UUID smId, String transitionName) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(fixture.bookTypeId())).findFirst().orElseThrow()
                .stateMachines().stream().filter(m -> m.id().equals(smId)).findFirst().orElseThrow()
                .states().stream().flatMap(s -> s.transitions().stream())
                .filter(t -> transitionName.equals(t.name()))
                .map(AdminTransitionView::id).findFirst().orElseThrow();
    }

    private UUID markerId(String name) {
        return authRepo.findItemTypeScopedMarkerByName(fixture.bookTypeId(), name).orElseThrow();
    }

    private Set<String> markerNamesOn(UUID book) {
        Set<UUID> ids = authRepo.getMarkerIdsForItem(book);
        return MARKER_NAMES.stream().filter(n -> ids.contains(markerId(n))).collect(Collectors.toSet());
    }

    private long itemUpdateLedgerRows(UUID book) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM ledger_entry
                WHERE target_type = 'ITEM' AND target_id = :itemId AND entry_type = 'ITEM_UPDATE' AND state = 'COMMITTED'
                """)
                .param("itemId", book).query(Long.class).single();
    }

    // --- tests ---

    @Test
    void startingIntoAStateWithAnEntryDecision_appliesItsMarkersWithStateProvenance() {
        Machine m = newMachine("smeMarkersAll", null);
        UUID book = createBook("SECRET");

        entityManager.startStateMachine(book, m.name(), SUPERUSER);

        assertThat(markerNamesOn(book)).containsExactlyInAnyOrder("SmeAlpha", "SmeBravo", "SmeCharlie");
        Map<UUID, MarkerAttribution> attribution = markerDecisionSupport.replayCurrentAttribution(book);
        for (String name : List.of("SmeAlpha", "SmeBravo", "SmeCharlie")) {
            assertThat(attribution.get(markerId(name)))
                    .isInstanceOfSatisfying(StateAppliedMarker.class, sam -> {
                        assertThat(sam.stateMachineId()).isEqualTo(m.smId());
                        assertThat(sam.stateId()).isEqualTo(m.s1());
                    });
        }
    }

    @Test
    void aSingleRuleWhoseMarkerCellIsAFeelList_appliesEveryNamedMarker() {
        // smeMarkersListCell has one rule whose markerName output cell is ["SmeAlpha","SmeBravo",
        // "SmeCharlie"] -- the shape the checkbox-list editor writes when several markers are ticked
        // for one rule. All three must land, from the single hit row.
        Machine m = newMachine("smeMarkersListCell", null);
        UUID book = createBook("SECRET");

        entityManager.startStateMachine(book, m.name(), SUPERUSER);

        assertThat(markerNamesOn(book)).containsExactlyInAnyOrder("SmeAlpha", "SmeBravo", "SmeCharlie");
        assertThat(markerDecisionSupport.replayCurrentAttribution(book).get(markerId("SmeBravo")))
                .isInstanceOfSatisfying(StateAppliedMarker.class, sam -> assertThat(sam.stateId()).isEqualTo(m.s1()));
    }

    @Test
    void startingIntoAStateWhoseDecisionMatchesNothing_appliesNoMarkers() {
        Machine m = newMachine("smeMarkersAll", null);
        UUID book = createBook("public title");

        entityManager.startStateMachine(book, m.name(), SUPERUSER);

        assertThat(markerNamesOn(book)).isEmpty();
    }

    @Test
    void transitioningToANarrowerEntryDecision_reconcilesInOneCommittedRow() {
        Machine m = newMachine("smeMarkersAll", "smeMarkersTwo");
        UUID book = createBook("SECRET");
        entityManager.startStateMachine(book, m.name(), SUPERUSER);
        assertThat(markerNamesOn(book)).containsExactlyInAnyOrder("SmeAlpha", "SmeBravo", "SmeCharlie");

        long before = itemUpdateLedgerRows(book);
        entityManager.executeTransition(book, m.name(), transitionId(m.smId(), "Advance"), SUPERUSER);

        assertThat(markerNamesOn(book)).containsExactlyInAnyOrder("SmeAlpha", "SmeBravo");
        assertThat(itemUpdateLedgerRows(book) - before).isEqualTo(1L);
        Map<UUID, MarkerAttribution> attribution = markerDecisionSupport.replayCurrentAttribution(book);
        assertThat(attribution).doesNotContainKey(markerId("SmeCharlie"));
        assertThat(attribution.get(markerId("SmeAlpha")))
                .isInstanceOfSatisfying(StateAppliedMarker.class, sam -> assertThat(sam.stateId()).isEqualTo(m.s2()));
    }

    @Test
    void transitioningIntoEnd_removesEveryStateConferredMarker() {
        Machine m = newMachine("smeMarkersAll", "smeMarkersAll");
        UUID book = createBook("SECRET");
        entityManager.startStateMachine(book, m.name(), SUPERUSER);
        entityManager.executeTransition(book, m.name(), transitionId(m.smId(), "Advance"), SUPERUSER);
        assertThat(markerNamesOn(book)).containsExactlyInAnyOrder("SmeAlpha", "SmeBravo", "SmeCharlie");

        entityManager.executeTransition(book, m.name(), transitionId(m.smId(), "Finish"), SUPERUSER);

        assertThat(registerPartitionManager.currentStateIds(book)).doesNotContainKey(m.smId());
        assertThat(markerNamesOn(book)).isEmpty();
    }

    @Test
    void aManuallyAppliedMarkerSurvivesAStateExitThatWouldOtherwiseDropIt() {
        Machine m = newMachine("smeMarkersAll", null); // S2 confers nothing
        UUID book = createBook("SECRET");
        entityManager.startStateMachine(book, m.name(), SUPERUSER);
        markerAssignmentService.addItemMarker(book, markerId("SmeAlpha"), "tester", "pinned by hand");

        entityManager.executeTransition(book, m.name(), transitionId(m.smId(), "Advance"), SUPERUSER);

        // SmeBravo/SmeCharlie were only state-conferred and are gone; SmeAlpha stays -- its current
        // attribution is a ManuallyAppliedMarker, which the state-exit reconciliation never touches.
        assertThat(markerNamesOn(book)).containsExactly("SmeAlpha");
    }

    @Test
    void anItemTypeRuleReassertsAMarkerThatAStateExitWouldHaveDropped() {
        Machine m = newMachine("smeMarkersAll", null); // S1 confers {A,B,C}; S2 confers nothing
        UUID book = createBook("SECRET");
        entityManager.startStateMachine(book, m.name(), SUPERUSER);
        assertThat(markerNamesOn(book)).containsExactlyInAnyOrder("SmeAlpha", "SmeBravo", "SmeCharlie");
        assertThat(markerDecisionSupport.replayCurrentAttribution(book).get(markerId("SmeCharlie")))
                .as("Charlie is only state-conferred at this point").isInstanceOf(StateAppliedMarker.class);

        // Introduce a rule that wants SmeCharlie only now -- the item is already sitting in S1.
        UUID ruleId = authRepo.createMarkerRule("SME wants Charlie", fixture.bookTypeId(), "smeRuleWantsCharlie").id();
        try {
            entityManager.executeTransition(book, m.name(), transitionId(m.smId(), "Advance"), SUPERUSER);

            // S1's state-conferred SmeAlpha/SmeBravo are dropped on exit into S2 (which confers
            // nothing); SmeCharlie survives because the item-type rule re-evaluated on this
            // transition (Part D's loosened gate) and re-asserted it into the same entry.
            assertThat(markerNamesOn(book)).containsExactly("SmeCharlie");
            assertThat(markerDecisionSupport.replayCurrentAttribution(book).get(markerId("SmeCharlie")))
                    .as("Charlie is now rule-attributed").isNotInstanceOf(StateAppliedMarker.class);
        } finally {
            jdbcClient.sql("DELETE FROM authorization_marker_rule WHERE id = :id").param("id", ruleId).update();
        }
    }

    @Test
    void aStateWhoseDecisionKeyIsNotDeployed_appliesNothingAndDoesNotThrow() {
        Machine m = newMachine("noSuchDecisionKey", null);
        UUID book = createBook("SECRET");

        entityManager.startStateMachine(book, m.name(), SUPERUSER);

        assertThat(markerNamesOn(book)).isEmpty();
        assertThat(registerPartitionManager.currentStateIds(book)).containsKey(m.smId());
    }
}

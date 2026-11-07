package org.ntrloc.graph.db.partition.register;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.EntityWriteService;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.ManualMarkerAssignmentService;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTransitionView;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.TestPrincipals;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.mutation.MutationValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers EntityWriteService.startStateMachine / executeTransition: permission (state-machine:start,
// transition:execute) checked first, the guard read from the register, END detach, and re-entry.
// Read-side (projection startable / availableTransitions) is covered in
// RegisterPartitionManagerProjectionIntegrationTest.
class StateMachineExecutionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EntityWriteService entityWriteService;
    @Autowired private LedgerRegisterCoordinator coordinator;
    @Autowired private RegisterStore registerStore;
    @Autowired private SchemaManager schemaManager;
    @Autowired private AuthorizationRepository authRepo;
    @Autowired private ManualMarkerAssignmentService markerAssignmentService;
    @Autowired private SecurityRepository securityRepo;
    @Autowired private RegisterProjectionTestDomainInitializer fixture;

    // Not static/final: needs a real, backed security_user row now that ledger_entry.created_by
    // (Part E) has a NOT NULL FK to security_user.id -- created fresh per test in setUp() via the
    // already-autowired securityRepo, which a static initializer can't reach.
    private NtrlocPrincipal ADMIN;

    @BeforeEach
    void setUpAdmin() {
        var user = securityRepo.createUser("Root", "sme-root-" + UUID.randomUUID() + "@example.com", true);
        ADMIN = TestPrincipals.of(user.id(), user.displayName(), user.email(), Set.of(), true);
    }

    private UUID createBook(String title) {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.bookTypeId(),
                Map.of(fixture.titlePropertyId(), title), Map.of(), Set.of())), txn, ADMIN.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    private UUID machineId() {
        return schemaManager.resolveStateMachineId(fixture.bookTypeId(),
                RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE);
    }

    private UUID transitionId(String name) {
        return machine().states().stream()
                .flatMap(s -> s.transitions().stream())
                .filter(t -> t.name().equals(name))
                .map(AdminTransitionView::id)
                .findFirst().orElseThrow();
    }

    private AdminStateMachineView machine() {
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(fixture.bookTypeId())).findFirst().orElseThrow()
                .stateMachines().stream().filter(m -> m.id().equals(machineId())).findFirst().orElseThrow();
    }

    private int status(Throwable t) {
        return ((ResponseStatusException) t).getStatusCode().value();
    }

    // --- start ---

    @Test
    void startStateMachine_entersTheStartTargetState() {
        UUID book = createBook("Dune");
        entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, ADMIN);

        UUID availableId = schemaManager.resolveStateId(machineId(), RegisterProjectionTestDomainInitializer.AVAILABLE);
        assertThat(registerStore.currentStateIds(book)).containsEntry(machineId(), availableId);
    }

    @Test
    void startStateMachine_whenAlreadyActive_throwsConflict() {
        UUID book = createBook("Dune");
        entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, ADMIN);

        assertThatThrownBy(() -> entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
    }

    @Test
    void startStateMachine_withoutTheStartGrant_throwsMutationValidationException() {
        UUID book = createBook("Dune");
        NtrlocPrincipal user = nonAdmin();

        assertThatThrownBy(() -> entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, user))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().contains("Not permitted to start")));
    }

    @Test
    void startStateMachine_startGrantViaAMarker_isHonoredAndRevocable() {
        UUID book = createBook("Dune");
        NtrlocPrincipal user = nonAdmin();
        var marker = authRepo.createMarker("sme-" + UUID.randomUUID(), "fixture", "ITEM_TYPE", fixture.bookTypeId());
        markerAssignmentService.addItemMarker(book, marker.id(), ADMIN, "test");
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "USER", user.id());
        authRepo.grantStateMachineStart(grantId, machineId());
        assertThat(authRepo.getStateMachineStartGrantsForMarker(marker.id(), "USER", user.id())).contains(machineId());

        entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, user);
        assertThat(registerStore.currentStateIds(book)).containsKey(machineId());

        // drive it (as admin) to Discontinued -> END so the machine is inactive again, then
        // revoke the start grant -- re-start must now be forbidden.
        UUID discontinued = schemaManager.resolveStateId(machineId(), RegisterProjectionTestDomainInitializer.DISCONTINUED);
        UUID endStateId = schemaManager.resolveStateId(machineId(), "__end__");
        schemaManager.applyMutations(List.<DefinitionMutation>of(
                new CreateTransitionMutation(discontinued, endStateId, "RevokeTestEnd", null, null, null)));
        entityWriteService.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("MarkOutOfStock"), ADMIN);
        entityWriteService.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("Discontinue"), ADMIN);
        entityWriteService.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("RevokeTestEnd"), ADMIN);
        assertThat(registerStore.currentStateIds(book)).doesNotContainKey(machineId());
        authRepo.revokeStateMachineStart(grantId, machineId());

        assertThatThrownBy(() -> entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, user))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().contains("Not permitted to start")));
    }

    // --- execute ---

    @Test
    void executeTransition_advancesToTheTargetState() {
        UUID book = createBook("Dune");
        entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, ADMIN);

        entityWriteService.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("MarkOutOfStock"), ADMIN);

        UUID outOfStock = schemaManager.resolveStateId(machineId(), RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);
        assertThat(registerStore.currentStateIds(book)).containsEntry(machineId(), outOfStock);
    }

    @Test
    void executeTransition_notAvailableFromTheCurrentState_throwsBadRequest() {
        UUID book = createBook("Dune");
        entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, ADMIN);

        // Restock is OutOfStock -> Available; not reachable from Available
        assertThatThrownBy(() -> entityWriteService.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("Restock"), ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(400));
    }

    @Test
    void executeTransition_whenMachineNotActive_throwsConflict() {
        UUID book = createBook("Dune");
        assertThatThrownBy(() -> entityWriteService.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("MarkOutOfStock"), ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
    }

    @Test
    void executeTransition_withoutTheExecuteGrant_throwsMutationValidationException() {
        UUID book = createBook("Dune");
        entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, ADMIN);
        NtrlocPrincipal user = nonAdmin();

        assertThatThrownBy(() -> entityWriteService.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("MarkOutOfStock"), user))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().contains("Not permitted to execute")));
    }

    @Test
    void executeTransition_withAnUnsatisfiedGuard_throwsUnprocessable() {
        UUID book = createBook("Dune");
        addGuardedRetire("{\"type\":\"PROPERTY_VALUE\",\"propertyName\":\"title\",\"operator\":\"EQUALS\",\"value\":\"NOPE\"}");
        entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, ADMIN);

        assertThatThrownBy(() -> entityWriteService.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("GuardedRetire"), ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(422));
    }

    // The guard reads the item's committed values from the register: a property by name, and its current
    // state in a machine by name.
    @Test
    void executeTransition_withASatisfiedGuard_advances() {
        UUID book = createBook("Dune");
        UUID discontinued = addGuardedRetire("""
                {"type":"AND","predicates":[
                  {"type":"PROPERTY_VALUE","propertyName":"title","operator":"EQUALS","value":"Dune"},
                  {"type":"STATE_VALUE","stateMachineName":"%s","stateName":"%s"}]}"""
                .formatted(RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, RegisterProjectionTestDomainInitializer.AVAILABLE));
        entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, ADMIN);

        entityWriteService.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("GuardedRetire"), ADMIN);

        assertThat(registerStore.currentStateIds(book)).containsEntry(machineId(), discontinued);
    }

    // Permission comes first: a caller who may not execute the transition learns only that, not whether
    // the guard would have passed.
    @Test
    void executeTransition_withoutTheExecuteGrant_reportsThatBeforeTheGuard() {
        UUID book = createBook("Dune");
        addGuardedRetire("{\"type\":\"PROPERTY_VALUE\",\"propertyName\":\"title\",\"operator\":\"EQUALS\",\"value\":\"NOPE\"}");
        entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, ADMIN);
        NtrlocPrincipal user = nonAdmin();

        assertThatThrownBy(() -> entityWriteService.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("GuardedRetire"), user))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().contains("Not permitted to execute")));
    }

    @Test
    void startStateMachine_withoutTheStartGrant_reportsThatBeforeAlreadyActive() {
        UUID book = createBook("Dune");
        entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, ADMIN);
        NtrlocPrincipal user = nonAdmin();

        assertThatThrownBy(() -> entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, user))
                .isInstanceOf(MutationValidationException.class)
                .satisfies(e -> assertThat(((MutationValidationException) e).errors())
                        .anyMatch(err -> err.message().contains("Not permitted to start")));
    }

    // Adds an Available -> Discontinued transition named GuardedRetire with the given guard, replacing one
    // an earlier test added (the fixture's schema is shared across the class); returns Discontinued's id.
    private UUID addGuardedRetire(String guardJson) {
        machine().states().stream().flatMap(s -> s.transitions().stream())
                .filter(t -> t.name().equals("GuardedRetire")).findFirst()
                .ifPresent(t -> schemaManager.applyMutations(List.<DefinitionMutation>of(new DeleteTransitionMutation(t.id()))));
        UUID available = schemaManager.resolveStateId(machineId(), RegisterProjectionTestDomainInitializer.AVAILABLE);
        UUID discontinued = schemaManager.resolveStateId(machineId(), RegisterProjectionTestDomainInitializer.DISCONTINUED);
        schemaManager.applyMutations(List.<DefinitionMutation>of(new CreateTransitionMutation(
                available, discontinued, "GuardedRetire", null, null, JsonMapper.builder().build().readTree(guardJson))));
        return discontinued;
    }

    // --- END detach + re-entry ---

    @Test
    void executeTransition_intoEnd_detachesTheMachine_andReStartWorks() {
        UUID book = createBook("Dune");
        UUID available = schemaManager.resolveStateId(machineId(), RegisterProjectionTestDomainInitializer.AVAILABLE);
        UUID endStateId = schemaManager.resolveStateId(machineId(), "__end__");
        schemaManager.applyMutations(List.<DefinitionMutation>of(
                new CreateTransitionMutation(available, endStateId, "Archive", null, null, null)));
        entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, ADMIN);

        entityWriteService.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("Archive"), ADMIN);
        assertThat(registerStore.currentStateIds(book)).doesNotContainKey(machineId());

        // re-entry
        entityWriteService.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, ADMIN);
        assertThat(registerStore.currentStateIds(book)).containsEntry(machineId(), available);
    }

    private NtrlocPrincipal nonAdmin() {
        var user = securityRepo.createUser("User", "sme-" + UUID.randomUUID() + "@example.com", false);
        return TestPrincipals.of(user.id(), user.displayName(), user.email(), Set.of(), false);
    }
}

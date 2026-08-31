package org.ntrloc.graph.db.partition.register;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.MarkerAssignmentService;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTransitionView;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.ResolvedPrincipal;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers EntityManager.startStateMachine / executeTransition -- permission (state-machine:start,
// transition:execute), guard enforcement, END detach, and re-entry. Read-side (projection
// startable / availableTransitions) is covered in RegisterPartitionManagerProjectionIntegrationTest.
class StateMachineExecutionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EntityManager entityManager;
    @Autowired private LedgerRegisterCoordinator coordinator;
    @Autowired private RegisterPartitionManager registerPartitionManager;
    @Autowired private SchemaManager schemaManager;
    @Autowired private AuthorizationRepository authRepo;
    @Autowired private MarkerAssignmentService markerAssignmentService;
    @Autowired private SecurityRepository securityRepo;
    @Autowired private RegisterProjectionTestDomainInitializer fixture;

    private static final NtrlocPrincipal SUPERUSER =
            new ResolvedPrincipal(UUID.randomUUID(), "sme-root", "Root", null, Set.of(), true);

    private UUID createBook(String title) {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.bookTypeId(),
                Map.of(fixture.titlePropertyId(), title), Map.of(), Set.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    private UUID machineId() {
        return registerPartitionManager.resolveStateMachineId(fixture.bookTypeId(),
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
        entityManager.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, SUPERUSER);

        UUID availableId = registerPartitionManager.resolveStateId(machineId(), RegisterProjectionTestDomainInitializer.AVAILABLE);
        assertThat(registerPartitionManager.currentStateIds(book)).containsEntry(machineId(), availableId);
    }

    @Test
    void startStateMachine_whenAlreadyActive_throwsConflict() {
        UUID book = createBook("Dune");
        entityManager.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, SUPERUSER);

        assertThatThrownBy(() -> entityManager.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, SUPERUSER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
    }

    @Test
    void startStateMachine_withoutTheStartGrant_throwsForbidden() {
        UUID book = createBook("Dune");
        NtrlocPrincipal user = nonSuperuser();

        assertThatThrownBy(() -> entityManager.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(403));
    }

    @Test
    void startStateMachine_startGrantViaAMarker_isHonoredAndRevocable() {
        UUID book = createBook("Dune");
        NtrlocPrincipal user = nonSuperuser();
        var marker = authRepo.createMarker("sme-" + UUID.randomUUID(), "fixture", "ITEM_TYPE", fixture.bookTypeId());
        markerAssignmentService.addItemMarker(book, marker.id(), "test", "test");
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "USER", user.id());
        authRepo.grantStateMachineStart(grantId, machineId());
        assertThat(authRepo.getStateMachineStartGrantsForMarker(marker.id(), "USER", user.id())).contains(machineId());

        entityManager.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, user);
        assertThat(registerPartitionManager.currentStateIds(book)).containsKey(machineId());

        // drive it (as superuser) to Discontinued -> END so the machine is inactive again, then
        // revoke the start grant -- re-start must now be forbidden.
        UUID discontinued = registerPartitionManager.resolveStateId(machineId(), RegisterProjectionTestDomainInitializer.DISCONTINUED);
        UUID endStateId = registerPartitionManager.resolveStateId(machineId(), "__end__");
        schemaManager.applyMutations(List.<DefinitionMutation>of(
                new CreateTransitionMutation(discontinued, endStateId, "RevokeTestEnd", null, null, null)));
        entityManager.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("MarkOutOfStock"), SUPERUSER);
        entityManager.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("Discontinue"), SUPERUSER);
        entityManager.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("RevokeTestEnd"), SUPERUSER);
        assertThat(registerPartitionManager.currentStateIds(book)).doesNotContainKey(machineId());
        authRepo.revokeStateMachineStart(grantId, machineId());

        assertThatThrownBy(() -> entityManager.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(403));
    }

    // --- execute ---

    @Test
    void executeTransition_advancesToTheTargetState() {
        UUID book = createBook("Dune");
        entityManager.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, SUPERUSER);

        entityManager.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("MarkOutOfStock"), SUPERUSER);

        UUID outOfStock = registerPartitionManager.resolveStateId(machineId(), RegisterProjectionTestDomainInitializer.OUT_OF_STOCK);
        assertThat(registerPartitionManager.currentStateIds(book)).containsEntry(machineId(), outOfStock);
    }

    @Test
    void executeTransition_notAvailableFromTheCurrentState_throwsBadRequest() {
        UUID book = createBook("Dune");
        entityManager.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, SUPERUSER);

        // Restock is OutOfStock -> Available; not reachable from Available
        assertThatThrownBy(() -> entityManager.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("Restock"), SUPERUSER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(400));
    }

    @Test
    void executeTransition_whenMachineNotActive_throwsConflict() {
        UUID book = createBook("Dune");
        assertThatThrownBy(() -> entityManager.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("MarkOutOfStock"), SUPERUSER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(409));
    }

    @Test
    void executeTransition_withoutTheExecuteGrant_throwsForbidden() {
        UUID book = createBook("Dune");
        entityManager.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, SUPERUSER);
        NtrlocPrincipal user = nonSuperuser();

        assertThatThrownBy(() -> entityManager.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("MarkOutOfStock"), user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(403));
    }

    @Test
    void executeTransition_withAnUnsatisfiedGuard_throwsUnprocessable() {
        UUID book = createBook("Dune");
        UUID available = registerPartitionManager.resolveStateId(machineId(), RegisterProjectionTestDomainInitializer.AVAILABLE);
        UUID discontinued = registerPartitionManager.resolveStateId(machineId(), RegisterProjectionTestDomainInitializer.DISCONTINUED);
        var guard = JsonMapper.builder().build().readTree(
                "{\"type\":\"PROPERTY_VALUE\",\"propertyName\":\"title\",\"operator\":\"EQUALS\",\"value\":\"NOPE\"}");
        schemaManager.applyMutations(List.<DefinitionMutation>of(
                new CreateTransitionMutation(available, discontinued, "GuardedRetire", null, null, guard)));
        entityManager.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, SUPERUSER);

        assertThatThrownBy(() -> entityManager.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("GuardedRetire"), SUPERUSER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(status(e)).isEqualTo(422));
    }

    // --- END detach + re-entry ---

    @Test
    void executeTransition_intoEnd_detachesTheMachine_andReStartWorks() {
        UUID book = createBook("Dune");
        UUID available = registerPartitionManager.resolveStateId(machineId(), RegisterProjectionTestDomainInitializer.AVAILABLE);
        UUID endStateId = registerPartitionManager.resolveStateId(machineId(), "__end__");
        schemaManager.applyMutations(List.<DefinitionMutation>of(
                new CreateTransitionMutation(available, endStateId, "Archive", null, null, null)));
        entityManager.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, SUPERUSER);

        entityManager.executeTransition(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, transitionId("Archive"), SUPERUSER);
        assertThat(registerPartitionManager.currentStateIds(book)).doesNotContainKey(machineId());

        // re-entry
        entityManager.startStateMachine(book, RegisterProjectionTestDomainInitializer.AVAILABILITY_MACHINE, SUPERUSER);
        assertThat(registerPartitionManager.currentStateIds(book)).containsEntry(machineId(), available);
    }

    private NtrlocPrincipal nonSuperuser() {
        var user = securityRepo.createUser("sme-" + UUID.randomUUID(), "User", null, false);
        return new ResolvedPrincipal(user.id(), user.externalId(), user.externalId(), null, Set.of(), false);
    }
}

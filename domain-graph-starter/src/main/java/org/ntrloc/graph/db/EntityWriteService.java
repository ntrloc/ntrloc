package org.ntrloc.graph.db;

import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.authorization.RequestPermissionContext;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.register.RegisterStoreReader;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTransitionView;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.mutation.AbortResponse;
import org.ntrloc.graph.mutation.CommitResponse;
import org.ntrloc.graph.mutation.MutationRequest;
import org.ntrloc.graph.mutation.MutationRequestProcessor;
import org.ntrloc.graph.mutation.MutationResponse;
import org.ntrloc.graph.mutation.MutationValidationException;
import org.ntrloc.graph.mutation.ValidationError;
import org.ntrloc.graph.projection.PredicateEvaluator;
import org.ntrloc.graph.projection.ProjectedItem;
import org.ntrloc.graph.projection.ProjectionEngine;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Entity Write (docs/ntrloc-architecture-rationalization.md §5): mutations, their two-phase confirmation,
// and state machine starts and transitions. May read (the register directly, unfiltered, and for a
// guard the item as projected); never read by the read side.
@Service
public class EntityWriteService {

    private final RegisterStoreReader registerStore;
    private final ProjectionEngine projectionEngine;
    private final SchemaManager schemaManager;
    private final PermissionService permissionService;
    private final MutationRequestProcessor mutationRequestProcessor;
    private final LedgerRegisterCoordinator coordinator;

    public EntityWriteService(RegisterStoreReader registerStore, ProjectionEngine projectionEngine, SchemaManager schemaManager,
                              PermissionService permissionService, MutationRequestProcessor mutationRequestProcessor,
                              LedgerRegisterCoordinator coordinator) {
        this.registerStore = registerStore;
        this.projectionEngine = projectionEngine;
        this.schemaManager = schemaManager;
        this.permissionService = permissionService;
        this.mutationRequestProcessor = mutationRequestProcessor;
        this.coordinator = coordinator;
    }

    // Pure passthrough: MutationRequestProcessor does its own permission checks per mutation (see
    // its class comment), and @PreAuthorize already guarantees principal is non-null by the time
    // this runs.
    @PreAuthorize("#principal != null")
    public MutationResponse mutate(MutationRequest request, NtrlocPrincipal principal) {
        return mutationRequestProcessor.process(request, principal);
    }

    public CommitResponse confirmCommit(UUID transactionId) {
        return mutationRequestProcessor.confirmCommit(transactionId);
    }

    public AbortResponse confirmAbort(UUID transactionId) {
        return mutationRequestProcessor.confirmAbort(transactionId);
    }

    @PreAuthorize("#principal != null")
    public void startStateMachine(UUID itemId, String stateMachineName, NtrlocPrincipal principal) {
        UUID itemTypeId = requireItemType(itemId);
        UUID smId = schemaManager.resolveStateMachineId(itemTypeId, stateMachineName);
        AdminStateMachineView machine = machineView(itemTypeId, smId);

        if (registerStore.currentStateIds(itemId).containsKey(smId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "State machine '" + stateMachineName + "' is already active on this item");
        }
        AdminStateView startState = machine.states().stream()
                .filter(s -> "START".equals(s.kind())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "State machine has no START state"));
        AdminTransitionView startTransition = startState.transitions().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "State machine '" + stateMachineName + "' has no start transition"));

        var ctx = permissionService.buildContext(principal);
        if (!permissionService.mayStartStateMachine(ctx, permissionService.markerIdsForItem(itemId), smId)) {
            // A permission denial is a mutation rejection like any other -- same
            // ValidationError/MutationValidationException vocabulary as MutationRequestProcessor's
            // own checks, not a separate ResponseStatusException mechanism (see
            // ApiExceptionHandler.handleMutationValidation for where this lands as a 400).
            throw new MutationValidationException(List.of(new ValidationError("stateMachine", "Not permitted to start this state machine")));
        }

        commitStateEntry(new ItemUpdateEntry(itemId, Map.of(), Map.of(smId, startTransition.toStateId()), Set.of(), Set.of(), Set.of()), principal);
    }

    @PreAuthorize("#principal != null")
    public void executeTransition(UUID itemId, String stateMachineName, UUID transitionId, NtrlocPrincipal principal) {
        UUID itemTypeId = requireItemType(itemId);
        UUID smId = schemaManager.resolveStateMachineId(itemTypeId, stateMachineName);
        AdminStateMachineView machine = machineView(itemTypeId, smId);

        UUID currentStateId = registerStore.currentStateIds(itemId).get(smId);
        if (currentStateId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "State machine '" + stateMachineName + "' is not active on this item");
        }
        AdminStateView currentState = machine.states().stream()
                .filter(s -> s.id().equals(currentStateId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Current state no longer exists in the schema"));
        AdminTransitionView transition = currentState.transitions().stream()
                .filter(t -> t.id().equals(transitionId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transition is not available from the current state"));

        var ctx = permissionService.buildContext(principal);
        if (!permissionService.mayExecuteTransition(ctx, permissionService.markerIdsForItem(itemId), transitionId)) {
            // See startStateMachine's own comment on why this is a MutationValidationException, not
            // a ResponseStatusException.
            throw new MutationValidationException(List.of(new ValidationError("stateMachine", "Not permitted to execute this transition")));
        }

        if (hasGuard(transition) && !guardSatisfied(itemTypeId, itemId, transition)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Transition guard not satisfied");
        }

        AdminStateView targetState = machine.states().stream()
                .filter(s -> s.id().equals(transition.toStateId())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Target state no longer exists in the schema"));
        ItemUpdateEntry entry = "END".equals(targetState.kind())
                ? new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(smId), Set.of(), Set.of())
                : new ItemUpdateEntry(itemId, Map.of(), Map.of(smId, targetState.id()), Set.of(), Set.of(), Set.of());
        commitStateEntry(entry, principal);
    }

    private UUID requireItemType(UUID itemId) {
        return registerStore.findItemTypeId(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown item: " + itemId));
    }

    private AdminStateMachineView machineView(UUID itemTypeId, UUID smId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemTypeId)).findFirst()
                .map(AdminItemDefinitionView::stateMachines).orElse(List.of()).stream()
                .filter(m -> m.id().equals(smId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown state machine"));
    }

    // A stored JSON null (NullNode) is not a real guard -- see SchemaRepository.serializeGuardCondition.
    private static boolean hasGuard(AdminTransitionView transition) {
        return transition.guardCondition() != null && !transition.guardCondition().isNull();
    }

    // Reads the item as projected, unfiltered (admin), since a guard is written against property and
    // state names. Step 5 of the refactor can read the register directly instead.
    private boolean guardSatisfied(UUID itemTypeId, UUID itemId, AdminTransitionView transition) {
        ProjectedItem item = projectionEngine
                .projectOne(itemTypeId, itemId, "", null, RequestPermissionContext.forAdmin())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown item: " + itemId));
        Map<String, String> currentStateByMachine = new HashMap<>();
        if (item.states() != null) {
            item.states().forEach((name, s) -> {
                if (s.currentState() != null) currentStateByMachine.put(name, s.currentState());
            });
        }
        return PredicateEvaluator.evaluate(
                PredicateEvaluator.fromJson(transition.guardCondition()), item.properties(), currentStateByMachine);
    }

    // The second write path into the coordinator (alongside MutationRequestProcessor); step 5 of the
    // refactor routes transitions through MutationRequestProcessor instead.
    private void commitStateEntry(ItemUpdateEntry entry, NtrlocPrincipal principal) {
        UUID transactionId = UUID.randomUUID();
        // principal is guaranteed non-null here -- both callers (startStateMachine,
        // executeTransition) carry @PreAuthorize.
        coordinator.prepare(List.of(entry), transactionId, principal.id(), OffsetDateTime.now(ZoneOffset.UTC));
        coordinator.commit(transactionId, UUID.randomUUID());
    }
}

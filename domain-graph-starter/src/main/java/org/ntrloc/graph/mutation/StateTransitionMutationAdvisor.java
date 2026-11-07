package org.ntrloc.graph.mutation;

import org.ntrloc.graph.db.partition.register.RegisterStoreReader;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTransitionView;
import org.ntrloc.graph.projection.PredicateEvaluator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Says what a state transition involves (docs/ntrloc-architecture-rationalization.md §5.2): the changes
// that put the item in the transition's target state, or why the transition can't apply. Advice only --
// MutationRequestProcessor checks permission before asking, decides, and submits. Reads the register
// directly, unfiltered, like the rest of the write side.
@Component
class StateTransitionMutationAdvisor {

    sealed interface Advice permits Changes, Rejection {}

    // The transition's own changes, kept apart from anything the caller sent so their provenance can be
    // recorded once transitions propose property or link changes (§6).
    record Changes(Map<UUID, UUID> stateChanges, Set<UUID> stateMachinesEnded) implements Advice {}

    record Rejection(HttpStatus status, String reason) implements Advice {}

    private final RegisterStoreReader registerStore;
    private final SchemaManager schemaManager;

    StateTransitionMutationAdvisor(RegisterStoreReader registerStore, SchemaManager schemaManager) {
        this.registerStore = registerStore;
        this.schemaManager = schemaManager;
    }

    // Starting a machine is its START state's transition.
    Advice adviseStart(UUID itemId, AdminStateMachineView machine, AdminTransitionView startTransition) {
        if (registerStore.currentStateIds(itemId).containsKey(machine.id())) {
            return new Rejection(HttpStatus.CONFLICT, "State machine '" + machine.name() + "' is already active on this item");
        }
        return new Changes(Map.of(machine.id(), startTransition.toStateId()), Set.of());
    }

    Advice adviseTransition(UUID itemId, UUID itemTypeId, AdminStateMachineView machine, AdminTransitionView transition) {
        Map<UUID, UUID> currentStateIds = registerStore.currentStateIds(itemId);
        UUID currentStateId = currentStateIds.get(machine.id());
        if (currentStateId == null) {
            return new Rejection(HttpStatus.CONFLICT, "State machine '" + machine.name() + "' is not active on this item");
        }
        Optional<AdminStateView> currentState = state(machine, currentStateId);
        if (currentState.isEmpty()) {
            return new Rejection(HttpStatus.CONFLICT, "Current state no longer exists in the schema");
        }
        if (currentState.get().transitions().stream().noneMatch(t -> t.id().equals(transition.id()))) {
            return new Rejection(HttpStatus.BAD_REQUEST, "Transition is not available from the current state");
        }
        if (hasGuard(transition) && !guardSatisfied(itemId, itemTypeId, currentStateIds, transition)) {
            return new Rejection(HttpStatus.UNPROCESSABLE_ENTITY, "Transition guard not satisfied");
        }
        Optional<AdminStateView> targetState = state(machine, transition.toStateId());
        if (targetState.isEmpty()) {
            return new Rejection(HttpStatus.CONFLICT, "Target state no longer exists in the schema");
        }
        return "END".equals(targetState.get().kind())
                ? new Changes(Map.of(), Set.of(machine.id()))
                : new Changes(Map.of(machine.id(), targetState.get().id()), Set.of());
    }

    private static Optional<AdminStateView> state(AdminStateMachineView machine, UUID stateId) {
        return machine.states().stream().filter(s -> s.id().equals(stateId)).findFirst();
    }

    // A stored JSON null (NullNode) is not a real guard -- see SchemaRepository.serializeGuardCondition.
    private static boolean hasGuard(AdminTransitionView transition) {
        return transition.guardCondition() != null && !transition.guardCondition().isNull();
    }

    // A guard is written against property and state names (top-level properties, as before), so the
    // register's id-keyed values are named through the schema.
    private boolean guardSatisfied(UUID itemId, UUID itemTypeId, Map<UUID, UUID> currentStateIds, AdminTransitionView transition) {
        AdminItemDefinitionView itemType = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemTypeId)).findFirst().orElseThrow();

        Map<UUID, Object> valuesById = registerStore.currentPropertiesById(itemId, itemTypeId);
        Map<String, Object> propertiesByName = new HashMap<>();
        for (AdminPropertyDefinitionView property : itemType.properties()) {
            Object value = valuesById.get(property.id());
            if (value != null) propertiesByName.put(property.name(), value);
        }

        Map<String, String> currentStateByMachine = new HashMap<>();
        for (AdminStateMachineView machine : Optional.ofNullable(itemType.stateMachines()).orElse(List.of())) {
            UUID stateId = currentStateIds.get(machine.id());
            if (stateId != null) state(machine, stateId).ifPresent(s -> currentStateByMachine.put(machine.name(), s.name()));
        }

        return PredicateEvaluator.evaluate(PredicateEvaluator.fromJson(transition.guardCondition()), propertiesByName, currentStateByMachine);
    }
}

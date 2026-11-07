package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetStateMachineLayoutMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.repository.SchemaStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;

// Applies state-machine/state/transition mutations -- split out of SchemaService (see its own
// history).
@Component
class StateMutationApplier {

    private final SchemaStore store;

    StateMutationApplier(SchemaStore store) {
        this.store = store;
    }

    // refIdToEntityId: the batch's refIds so far -- a state created with one is recorded in it, and a
    // layout's keys are resolved against it.
    boolean apply(DefinitionMutation mutation, Map<String, UUID> refIdToEntityId) {
        switch (mutation) {
            case CreateStateMachineMutation m -> {
                var machine = store.createStateMachine(m.itemDefinitionId(), m.name(), m.description());
                // Every machine is born with its START and END pseudostates -- undeletable, sentinel-named.
                store.createPseudoState(machine.id(), SchemaStore.STATE_KIND_START);
                store.createPseudoState(machine.id(), SchemaStore.STATE_KIND_END);
            }
            case UpdateStateMachineMutation m -> store.updateStateMachine(m.id(), m.name(), m.description());
            case DeleteStateMachineMutation m -> store.deleteStateMachine(m.id());
            case SetStateMachineLayoutMutation m ->
                    store.setStateMachineLayout(m.stateMachineId(), withRefIdsResolved(m.layout(), refIdToEntityId));
            case CreateStateMutation m -> {
                SchemaMutationValidation.requireNotReservedStateName(m.name());
                var state = store.createState(m.stateMachineId(), m.name(), m.description(), SchemaStore.STATE_KIND_NORMAL,
                        m.entryProcessId(), m.exitProcessId());
                if (m.refId() != null) refIdToEntityId.put(m.refId(), state.id());
            }
            case UpdateStateMutation m -> {
                SchemaMutationValidation.requireNotReservedStateName(m.name());
                SchemaMutationValidation.requireDeletableState(store, m.id()); // NORMAL-only -- same gate as delete
                store.updateState(m.id(), m.name(), m.description(), m.entryProcessId(), m.exitProcessId());
            }
            case DeleteStateMutation m -> {
                SchemaMutationValidation.requireDeletableState(store, m.id());
                store.deleteState(m.id());
            }
            case CreateTransitionMutation m -> {
                String guard = store.serializeGuardCondition(m.guardCondition());
                UUID from = m.fromStateId() != null ? m.fromStateId() : stateId(m.fromState(), refIdToEntityId);
                UUID to = m.toStateId() != null ? m.toStateId() : stateId(m.toState(), refIdToEntityId);
                SchemaMutationValidation.requireValidTransitionEndpoints(store, from, to, guard, null);
                var transition = store.createTransition(from, to, m.name(), m.description(), m.processId(), guard);
                if (m.refId() != null) refIdToEntityId.put(m.refId(), transition.id());
            }
            case UpdateTransitionMutation m -> {
                String guard = store.serializeGuardCondition(m.guardCondition());
                moveTransition(m, guard, refIdToEntityId);
                SchemaMutationValidation.requireGuardAllowedOnTransition(store, m.id(), guard);
                store.updateTransition(m.id(), m.name(), m.description(), m.processId(), guard);
            }
            case DeleteTransitionMutation m -> store.deleteTransition(m.id());
            default -> {
                return false;
            }
        }
        return true;
    }

    // Moves a transition to the states an update names, if it names any others.
    private void moveTransition(UpdateTransitionMutation m, String guard, Map<String, UUID> refIdToEntityId) {
        if (m.fromState() == null && m.toState() == null) return;
        var current = store.findTransition(m.id())
                .orElseThrow(() -> new IllegalArgumentException("Unknown transition: " + m.id()));
        UUID from = m.fromState() == null ? current.fromStateId() : stateId(m.fromState(), refIdToEntityId);
        UUID to = m.toState() == null ? current.toStateId() : stateId(m.toState(), refIdToEntityId);
        if (from.equals(current.fromStateId()) && to.equals(current.toStateId())) return;
        var machineId = store.findState(current.fromStateId()).orElseThrow().stateMachineId();
        if (!store.findState(from).map(s -> s.stateMachineId().equals(machineId)).orElse(true)) {
            throw new IllegalArgumentException("A transition can't be moved to another state machine");
        }
        SchemaMutationValidation.requireValidTransitionEndpoints(store, from, to, guard, m.id());
        store.updateTransitionEndpoints(m.id(), from, to);
    }

    // A state's id, or the refId of one created earlier in the batch.
    private static UUID stateId(String reference, Map<String, UUID> refIdToEntityId) {
        if (reference == null) throw new IllegalArgumentException("A transition needs both its states");
        UUID id = refIdToEntityId.get(reference);
        if (id != null) return id;
        try {
            return UUID.fromString(reference);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown state: " + reference, e);
        }
    }

    // The layout with each key that's a refId replaced by its id, at any depth.
    private static JsonNode withRefIdsResolved(JsonNode node, Map<String, UUID> refIdToEntityId) {
        if (node == null || refIdToEntityId.isEmpty()) return node;
        if (node instanceof ObjectNode object) {
            ObjectNode resolved = object.objectNode();
            for (var entry : object.properties()) {
                UUID id = refIdToEntityId.get(entry.getKey());
                resolved.set(id != null ? id.toString() : entry.getKey(), withRefIdsResolved(entry.getValue(), refIdToEntityId));
            }
            return resolved;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode resolved = array.arrayNode();
            array.forEach(element -> resolved.add(withRefIdsResolved(element, refIdToEntityId)));
            return resolved;
        }
        return node;
    }
}

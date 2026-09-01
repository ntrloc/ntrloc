package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.stereotype.Component;

// Applies state-machine/state/transition mutations -- split out of SchemaManager (see its own
// history).
@Component
class StateMutationApplier {

    private final SchemaRepository repo;

    StateMutationApplier(SchemaRepository repo) {
        this.repo = repo;
    }

    boolean apply(DefinitionMutation mutation) {
        if (mutation instanceof CreateStateMachineMutation m) {
            var machine = repo.createStateMachine(m.itemDefinitionId(), m.name(), m.description());
            // Every machine is born with its START and END pseudostates -- undeletable, sentinel-named.
            repo.createPseudoState(machine.id(), SchemaRepository.STATE_KIND_START);
            repo.createPseudoState(machine.id(), SchemaRepository.STATE_KIND_END);
        } else if (mutation instanceof UpdateStateMachineMutation m) {
            repo.updateStateMachine(m.id(), m.name(), m.description());
        } else if (mutation instanceof DeleteStateMachineMutation m) {
            repo.deleteStateMachine(m.id());
        } else if (mutation instanceof CreateStateMutation m) {
            SchemaMutationValidation.requireNotReservedStateName(m.name());
            repo.createState(m.stateMachineId(), m.name(), m.description(), SchemaRepository.STATE_KIND_NORMAL,
                    m.entryProcessId(), m.exitProcessId(), m.entryMarkerDecisionKey());
        } else if (mutation instanceof UpdateStateMutation m) {
            SchemaMutationValidation.requireNotReservedStateName(m.name());
            SchemaMutationValidation.requireDeletableState(repo, m.id()); // NORMAL-only -- same gate as delete
            repo.updateState(m.id(), m.name(), m.description(), m.entryProcessId(), m.exitProcessId(), m.entryMarkerDecisionKey());
        } else if (mutation instanceof DeleteStateMutation m) {
            SchemaMutationValidation.requireDeletableState(repo, m.id());
            repo.deleteState(m.id());
        } else if (mutation instanceof CreateTransitionMutation m) {
            String guard = repo.serializeGuardCondition(m.guardCondition());
            SchemaMutationValidation.requireValidTransitionEndpoints(repo, m.fromStateId(), m.toStateId(), guard);
            repo.createTransition(m.fromStateId(), m.toStateId(), m.name(), m.description(), m.processId(), guard);
        } else if (mutation instanceof UpdateTransitionMutation m) {
            String guard = repo.serializeGuardCondition(m.guardCondition());
            SchemaMutationValidation.requireGuardAllowedOnTransition(repo, m.id(), guard);
            repo.updateTransition(m.id(), m.name(), m.description(), m.processId(), guard);
        } else if (mutation instanceof DeleteTransitionMutation m) {
            repo.deleteTransition(m.id());
        } else {
            return false;
        }
        return true;
    }
}

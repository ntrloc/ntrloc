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
            repo.createStateMachine(m.itemDefinitionId(), m.name(), m.description());
        } else if (mutation instanceof UpdateStateMachineMutation m) {
            repo.updateStateMachine(m.id(), m.name(), m.description());
        } else if (mutation instanceof DeleteStateMachineMutation m) {
            repo.deleteStateMachine(m.id());
        } else if (mutation instanceof CreateStateMutation m) {
            repo.createState(m.stateMachineId(), m.name(), m.description(), m.isInitial(), m.entryProcessId(), m.exitProcessId());
        } else if (mutation instanceof UpdateStateMutation m) {
            repo.updateState(m.id(), m.name(), m.description(), m.isInitial(), m.entryProcessId(), m.exitProcessId());
        } else if (mutation instanceof DeleteStateMutation m) {
            repo.deleteState(m.id());
        } else if (mutation instanceof CreateTransitionMutation m) {
            repo.createTransition(m.fromStateId(), m.toStateId(), m.name(), m.description(), m.processId(), repo.serializeGuardCondition(m.guardCondition()));
        } else if (mutation instanceof UpdateTransitionMutation m) {
            repo.updateTransition(m.id(), m.name(), m.description(), m.processId(), repo.serializeGuardCondition(m.guardCondition()));
        } else if (mutation instanceof DeleteTransitionMutation m) {
            repo.deleteTransition(m.id());
        } else {
            return false;
        }
        return true;
    }
}

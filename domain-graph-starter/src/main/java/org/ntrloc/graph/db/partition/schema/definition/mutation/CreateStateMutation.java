package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

// Only ever creates NORMAL states -- the START/END pseudostates are created with the machine
// (CreateStateMachineMutation) and are not mutable through this. refId is optional: a caller's tag
// for the new state, so a SetStateMachineLayoutMutation later in the same batch can place it before
// it has an id.
public record CreateStateMutation(UUID stateMachineId, String name, @Nullable String description,
                                  @Nullable String entryProcessId, @Nullable String exitProcessId,
                                  @Nullable String refId) implements DefinitionMutation {

    public CreateStateMutation(UUID stateMachineId, String name, @Nullable String description,
                               @Nullable String entryProcessId, @Nullable String exitProcessId) {
        this(stateMachineId, name, description, entryProcessId, exitProcessId, null);
    }
}

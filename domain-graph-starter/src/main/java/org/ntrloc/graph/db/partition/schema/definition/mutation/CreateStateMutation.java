package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

public record CreateStateMutation(UUID stateMachineId, String name, @Nullable String description, boolean isInitial, @Nullable String entryProcessId, @Nullable String exitProcessId) implements DefinitionMutation {
}

package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

public record CreateStateMachineMutation(UUID itemDefinitionId, String name, @Nullable String description) implements DefinitionMutation {
}

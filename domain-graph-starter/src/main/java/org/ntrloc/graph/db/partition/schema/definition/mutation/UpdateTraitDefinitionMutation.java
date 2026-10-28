package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

public record UpdateTraitDefinitionMutation(UUID id, String name, @Nullable String description) implements DefinitionMutation {
}

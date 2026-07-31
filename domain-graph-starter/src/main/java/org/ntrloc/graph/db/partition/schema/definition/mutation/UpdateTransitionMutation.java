package org.ntrloc.graph.db.partition.schema.definition.mutation;

import tools.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

import java.util.UUID;

public record UpdateTransitionMutation(UUID id, String name, @Nullable String description, @Nullable String processId, @Nullable JsonNode guardCondition) implements DefinitionMutation {
}

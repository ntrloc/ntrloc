package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import tools.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

import java.util.UUID;

public record AdminTransitionView(UUID id, UUID toStateId, String toStateName, String name, @Nullable String description, @Nullable String processId, @Nullable JsonNode guardCondition) {
}

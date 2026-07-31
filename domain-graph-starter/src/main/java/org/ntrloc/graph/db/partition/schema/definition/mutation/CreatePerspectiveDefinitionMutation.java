package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.UUID;

public record CreatePerspectiveDefinitionMutation(UUID itemId, String name, String description, Integer minCardinality, Integer maxCardinality) {
}

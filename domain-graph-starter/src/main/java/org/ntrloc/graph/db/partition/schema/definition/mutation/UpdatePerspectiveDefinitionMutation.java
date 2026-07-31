package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.UUID;

public record UpdatePerspectiveDefinitionMutation(UUID id, String name, String description, Integer minCardinality, Integer maxCardinality) implements DefinitionMutation {
}

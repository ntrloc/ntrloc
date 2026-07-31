package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.UUID;

public record UpdateItemDefinitionMutation(UUID id, String name, String description) implements DefinitionMutation {
}

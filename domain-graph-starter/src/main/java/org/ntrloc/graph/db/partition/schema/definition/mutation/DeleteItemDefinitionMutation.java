package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.UUID;

public record DeleteItemDefinitionMutation(UUID id) implements DefinitionMutation {
}

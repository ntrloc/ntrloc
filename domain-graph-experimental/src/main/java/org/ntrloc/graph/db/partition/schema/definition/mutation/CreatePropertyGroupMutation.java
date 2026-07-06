package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.UUID;

public record CreatePropertyGroupMutation(UUID entityId, String name) implements DefinitionMutation {
}

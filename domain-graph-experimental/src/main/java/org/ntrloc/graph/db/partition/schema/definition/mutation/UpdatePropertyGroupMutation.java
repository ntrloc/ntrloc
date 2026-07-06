package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.UUID;

public record UpdatePropertyGroupMutation(UUID id, String name) implements DefinitionMutation {
}

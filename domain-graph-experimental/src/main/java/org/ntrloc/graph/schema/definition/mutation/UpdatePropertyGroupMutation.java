package org.ntrloc.graph.schema.definition.mutation;

import java.util.UUID;

public record UpdatePropertyGroupMutation(UUID id, String name) implements DefinitionMutation {
}

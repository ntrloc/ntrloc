package org.ntrloc.graph.schema.definition.mutation;

import java.util.UUID;

public record DeleteItemDefinitionMutation(UUID id) implements DefinitionMutation {
}

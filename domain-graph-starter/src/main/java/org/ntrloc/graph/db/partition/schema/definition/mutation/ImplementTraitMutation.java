package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.UUID;

public record ImplementTraitMutation(UUID itemId, UUID traitId) implements DefinitionMutation {}

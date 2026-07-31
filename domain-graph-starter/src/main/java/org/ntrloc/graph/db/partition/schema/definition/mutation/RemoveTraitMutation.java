package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.UUID;

public record RemoveTraitMutation(UUID itemId, UUID traitId) implements DefinitionMutation {}

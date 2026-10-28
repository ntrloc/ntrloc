package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

// Changes nothing: fails the batch with a SchemaConflictException unless the trait's own fields still
// hold these values -- the trait counterpart to ExpectItemDefinitionMutation.
public record ExpectTraitDefinitionMutation(UUID id, String name, @Nullable String description) implements DefinitionMutation {
}

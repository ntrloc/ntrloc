package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

// Changes nothing: fails the batch with a SchemaConflictException unless the item type's own fields
// still hold these values. An editor puts it first in a save, with the values it started from, so
// someone else's change made in the meantime is reported instead of overwritten.
public record ExpectItemDefinitionMutation(UUID id, String name, @Nullable String description,
                                           @Nullable UUID supertypeId, boolean abstractType,
                                           @Nullable String displayLabelPattern) implements DefinitionMutation {
}

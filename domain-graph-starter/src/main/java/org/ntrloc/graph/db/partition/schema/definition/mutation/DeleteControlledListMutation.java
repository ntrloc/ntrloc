package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.UUID;

// Drops the list. Any property using it is auto-detached (schema_property.controlled_list_id FK is
// ON DELETE SET NULL) -- list values are advisory, so no item data is affected.
public record DeleteControlledListMutation(UUID listId) implements DefinitionMutation {
}

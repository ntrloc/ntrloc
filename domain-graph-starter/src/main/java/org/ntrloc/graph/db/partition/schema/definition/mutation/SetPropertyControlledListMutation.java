package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

// Attaches an existing controlled list to a property, or detaches it (listId == null). The list's
// valueType must match the property's type (STRING/INT/LONG only).
public record SetPropertyControlledListMutation(UUID propertyId, @Nullable UUID listId)
        implements DefinitionMutation {
}

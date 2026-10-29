package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

// Attaches a controlled list to a property, or detaches it (list == null). `list` is either a
// NewControlledListReference (a list CreateControlledListMutation created earlier in this same
// request, by its own refId) or an ExistingControlledListReference (a real, already-persisted
// list id) -- see ControlledListReference's own comment. The list's valueType must match the
// property's type (one of ControlledListValues.SUPPORTED_TYPES).
public record SetPropertyControlledListMutation(UUID propertyId, @Nullable ControlledListReference list)
        implements DefinitionMutation {
}

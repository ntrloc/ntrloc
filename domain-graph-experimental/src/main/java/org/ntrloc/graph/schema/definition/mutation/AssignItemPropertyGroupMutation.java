package org.ntrloc.graph.schema.definition.mutation;

import java.util.UUID;

public record AssignItemPropertyGroupMutation(UUID itemId, UUID propertyId, UUID groupId) implements DefinitionMutation {
}

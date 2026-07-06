package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.UUID;

public record AssignItemPropertyGroupMutation(UUID itemId, UUID propertyId, UUID groupId) implements DefinitionMutation {
}

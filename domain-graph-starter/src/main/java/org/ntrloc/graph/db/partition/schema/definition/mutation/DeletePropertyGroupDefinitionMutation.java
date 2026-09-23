package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.UUID;

// Rejected unless the group is empty -- a group that still contains anything is never deleted, and
// never cascades into what it holds.
public record DeletePropertyGroupDefinitionMutation(UUID id) implements DefinitionMutation {
}

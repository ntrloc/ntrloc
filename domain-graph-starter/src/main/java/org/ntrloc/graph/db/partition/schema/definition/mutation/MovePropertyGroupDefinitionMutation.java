package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;

import java.util.UUID;

// Moves an existing property group, with everything in it, to a new parent. The current parent is
// looked up server-side, not supplied by the caller, so a stale client can't move the wrong thing.
// Storage is untouched: register/ledger values are keyed by property id, never by containment path.
public record MovePropertyGroupDefinitionMutation(UUID groupId, PropertyContainerKind targetKind, UUID targetId) implements DefinitionMutation {
}

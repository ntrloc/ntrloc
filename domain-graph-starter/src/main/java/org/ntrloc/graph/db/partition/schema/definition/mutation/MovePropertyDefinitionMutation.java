package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;

import java.util.UUID;

// Moves an existing property to a new parent -- an item type, a trait, a link type, or a property
// group. The property's current parent is looked up server-side (SchemaRepository.
// findPropertyParent), not supplied by the caller, so a stale client can't move the wrong thing.
// Storage is untouched: register/ledger values are keyed by property id, never by containment
// path, so this only changes which parent column is set.
public record MovePropertyDefinitionMutation(UUID propertyId, PropertyContainerKind targetKind, UUID targetId) implements DefinitionMutation {
}

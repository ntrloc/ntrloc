package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;

import java.util.UUID;

// Moves an existing property to a new container -- e.g. into or out of an object property, or
// between two object properties. The property's current container is looked up server-side
// (SchemaRepository.findCurrentOwner), not supplied by the caller, so a stale client can't
// dissociate the wrong thing. Storage is untouched: this is purely a schema_*_property join-table
// change, since register/ledger values are keyed by property id, never by containment path.
public record MovePropertyDefinitionMutation(UUID propertyId, PropertyContainerKind targetKind, UUID targetId) implements DefinitionMutation {
}

package org.ntrloc.graph.db.partition.schema.event;

import java.util.UUID;

// Published by SchemaManager whenever a schema definition mutation actually commits, so other
// partitions can react without SchemaManager needing to know who's listening (e.g. the register
// partition creates/drops its own per-item-type and per-link-type tables in response).
public sealed interface SchemaChangeEvent {

    record ItemTypeCreated(UUID itemTypeId) implements SchemaChangeEvent {}

    record ItemTypeDeleted(UUID itemTypeId) implements SchemaChangeEvent {}

    record LinkTypeCreated(UUID linkTypeId) implements SchemaChangeEvent {}

    record LinkTypeDeleted(UUID linkTypeId) implements SchemaChangeEvent {}

    // Traits have no register-side storage of their own -- their properties live in the JSONB
    // blob of whichever item type implements them -- but the event still fires so listeners
    // that do care about traits (e.g. future ones) don't need SchemaManager to change again.
    record TraitCreated(UUID traitId) implements SchemaChangeEvent {}

    record TraitDeleted(UUID traitId) implements SchemaChangeEvent {}
}

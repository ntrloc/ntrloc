package org.ntrloc.graph.db.partition.ledger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

// properties is keyed by property id (schema_property.id), never by name -- names are mutable
// (UpdatePropertyDefinitionMutation can rename a property), so a name-keyed entry would silently
// disconnect from what it actually refers to after a rename. The ledger must survive that. Same
// id-not-name rule for initialStates (state machine id -> state id) and initialMarkers.
//
// initialStates/initialMarkers exist alongside properties, not folded into it, because creation
// is just the starting point of the same three facets ItemUpdateEntry carries as deltas --
// properties, state, and markers were never really three different kinds of "item change," just
// three facets of one.
public record ItemCreateEntry(UUID itemId, UUID itemTypeId, Map<UUID, Object> properties,
                               Map<UUID, UUID> initialStates, Set<MarkerAttribution> initialMarkers) implements LedgerEntry {

    // Null-normalizing compact constructor -- every real construction site already passes Map.of()/
    // Set.of() for an empty facet, never null, but a ledger entry stored before initialMarkers
    // existed (or any payload simply missing a key) deserializes a missing Map/Set field as null,
    // not empty (Jackson gives records no per-field default). Every reader of these facets assumes
    // "non-null, possibly empty" -- see ItemUpdateEntry's own callers -- so normalizing here, once,
    // is safer than requiring every reader (present and future) to null-check first.
    public ItemCreateEntry {
        if (properties == null) properties = Map.of();
        if (initialStates == null) initialStates = Map.of();
        if (initialMarkers == null) initialMarkers = Set.of();
    }
}

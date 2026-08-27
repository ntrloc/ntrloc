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
}

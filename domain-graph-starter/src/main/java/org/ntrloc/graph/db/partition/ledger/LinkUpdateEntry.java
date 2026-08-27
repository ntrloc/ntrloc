package org.ntrloc.graph.db.partition.ledger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

// properties is a diff keyed by property id, not name (see ItemCreateEntry): an absent key
// leaves that property unchanged, a null value clears it. markersAdded/markersRemoved are set
// deltas, not a wholesale replacement -- see ItemUpdateEntry's own comment. No state facet --
// only items participate in state machines.
public record LinkUpdateEntry(UUID linkId, Map<UUID, Object> properties,
                               Set<MarkerAttribution> markersAdded, Set<MarkerAttribution> markersRemoved) implements LedgerEntry {
}

package org.ntrloc.graph.db.partition.ledger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

// properties is keyed by property id, not name (see ItemCreateEntry). Exactly two endpoints --
// a List here would let an invalid arity (0, 1, 3+) compile; the register model is binary
// (register_item_link_perspective's own read-side join assumes exactly two sides per link).
// No state facet -- only items participate in state machines (see ItemCreateEntry's own comment
// on why initialMarkers sits alongside properties rather than being a separate entry type).
public record LinkCreateEntry(UUID linkId, UUID linkTypeId, LinkEndpoint endpointA, LinkEndpoint endpointB,
                               Map<UUID, Object> properties, Set<MarkerAttribution> initialMarkers) implements LedgerEntry {
}

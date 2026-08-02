package org.ntrloc.graph.db.partition.ledger;

import java.util.Map;
import java.util.UUID;

// properties is keyed by property id, not name (see ItemCreateEntry). Exactly two endpoints --
// a List here would let an invalid arity (0, 1, 3+) compile; the register model is binary
// (register_item_link_perspective's own read-side join assumes exactly two sides per link).
public record LinkCreateEntry(UUID linkId, UUID linkTypeId, LinkEndpoint endpointA, LinkEndpoint endpointB,
                               Map<UUID, Object> properties) implements LedgerEntry {
}

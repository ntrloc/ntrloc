package org.ntrloc.graph.db.partition.ledger;

import java.util.Map;
import java.util.UUID;

// properties is a diff keyed by property id, not name (see ItemCreateEntry): an absent key
// leaves that property unchanged, a null value clears it.
public record LinkUpdateEntry(UUID linkId, Map<UUID, Object> properties) implements LedgerEntry {
}

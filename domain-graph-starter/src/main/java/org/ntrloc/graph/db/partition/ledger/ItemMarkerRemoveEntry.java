package org.ntrloc.graph.db.partition.ledger;

import java.util.UUID;

public record ItemMarkerRemoveEntry(UUID itemId, UUID markerId) implements LedgerEntry {
}

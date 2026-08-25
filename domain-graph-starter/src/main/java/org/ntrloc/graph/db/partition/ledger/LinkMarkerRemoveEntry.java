package org.ntrloc.graph.db.partition.ledger;

import java.util.UUID;

public record LinkMarkerRemoveEntry(UUID linkId, UUID markerId) implements LedgerEntry {
}

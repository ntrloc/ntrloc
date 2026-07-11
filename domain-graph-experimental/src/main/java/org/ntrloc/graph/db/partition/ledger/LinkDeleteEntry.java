package org.ntrloc.graph.db.partition.ledger;

import java.util.UUID;

public record LinkDeleteEntry(UUID linkId) implements LedgerEntry {
}

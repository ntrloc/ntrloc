package org.ntrloc.graph.db.partition.ledger;

import java.util.UUID;

public record ItemDeleteEntry(UUID itemId) implements LedgerEntry {
}

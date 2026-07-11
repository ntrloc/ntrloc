package org.ntrloc.graph.db.partition.ledger;

import java.util.Map;
import java.util.UUID;

// properties is a diff: an absent key leaves that property unchanged, a null value clears it.
public record ItemUpdateEntry(UUID itemId, Map<String, Object> properties) implements LedgerEntry {
}

package org.ntrloc.graph.db.partition.ledger;

import java.util.Map;
import java.util.UUID;

public record ItemCreateEntry(UUID itemId, UUID itemTypeId, Map<String, Object> properties) implements LedgerEntry {
}

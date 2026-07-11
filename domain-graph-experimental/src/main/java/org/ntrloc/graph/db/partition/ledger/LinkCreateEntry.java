package org.ntrloc.graph.db.partition.ledger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LinkCreateEntry(UUID linkId, UUID linkTypeId, List<LinkEndpoint> endpoints,
                               Map<String, Object> properties) implements LedgerEntry {
}

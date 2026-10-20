package org.ntrloc.graph.db.partition.ledger;

import java.time.OffsetDateTime;
import java.util.UUID;

// A LedgerEntry plus the row metadata readItemStream/readLinkStream themselves discard.
public record LedgerEntryRecord(LedgerEntry entry, OffsetDateTime createdDate, UUID createdBy) {
}

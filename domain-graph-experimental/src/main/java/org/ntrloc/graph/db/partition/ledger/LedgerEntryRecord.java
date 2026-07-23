package org.ntrloc.graph.db.partition.ledger;

import java.time.OffsetDateTime;

// A LedgerEntry plus the row metadata readItemStream/readLinkStream themselves discard --
// actorExternalId is nullable (LedgerInitializer's own note: not every caller has a resolvable
// principal).
public record LedgerEntryRecord(LedgerEntry entry, OffsetDateTime createdAt, String actorExternalId) {
}

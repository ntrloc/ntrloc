package org.ntrloc.graph.db.partition.ledger;

import java.util.List;
import java.util.UUID;

public interface LedgerPartitionManager {

    // Writes entries as UNCOMMITTED under transactionId. Not visible via readItemStream/
    // readLinkStream until commit(transactionId) is called. actorExternalId is a single value for
    // the whole batch, not per-entry -- everything appended together in one call came from one
    // mutate() request, so it shares one actor; may be null (actor_external_id is nullable by
    // design).
    void append(List<LedgerEntry> entries, UUID transactionId, String actorExternalId);

    void commit(UUID transactionId, UUID commitId);

    // Deletes transactionId's UNCOMMITTED entries outright, rather than marking them aborted.
    void abort(UUID transactionId);

    List<LedgerEntry> readItemStream(UUID itemId);

    List<LedgerEntry> readLinkStream(UUID linkId);

    // Same committed item stream as readItemStream, but with the per-row metadata
    // (createdAt/actorExternalId) readItemStream itself discards -- needed for per-property edit
    // history (LedgerPropertyHistoryService), not for anything readItemStream's existing callers
    // (register materialization) ever needed.
    List<LedgerEntryRecord> readItemStreamWithMetadata(UUID itemId);

    // Returns transactionId's entries regardless of state, in append order. The caller of
    // append() may not be the same process that later calls commit()/abort() (Section 11's
    // future cross-domain door), so the transaction's entries must be re-fetchable from just
    // its id rather than assuming an in-memory list survives across that boundary.
    List<LedgerEntry> readTransaction(UUID transactionId);
}

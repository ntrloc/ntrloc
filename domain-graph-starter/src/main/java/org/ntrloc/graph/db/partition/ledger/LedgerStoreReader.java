package org.ntrloc.graph.db.partition.ledger;

import java.util.List;
import java.util.UUID;

/** Reading the ledger. Anything may read it; it's hidden from projections, which read the register. */
public interface LedgerStoreReader {

    List<LedgerEntry> readItemStream(UUID itemId);

    List<LedgerEntry> readLinkStream(UUID linkId);

    // Same committed item stream as readItemStream, but with the per-row metadata
    // (createdDate/createdBy) readItemStream itself discards -- needed for per-property edit
    // history (LedgerPropertyHistoryService), not for anything readItemStream's existing callers
    // (register materialization) ever needed.
    List<LedgerEntryRecord> readItemStreamWithMetadata(UUID itemId);

    // Returns transactionId's entries regardless of state, in append order. The caller of
    // append() may not be the same process that later calls commit()/abort() (Section 11's
    // future cross-domain door), so the transaction's entries must be re-fetchable from just
    // its id rather than assuming an in-memory list survives across that boundary.
    List<LedgerEntry> readTransaction(UUID transactionId);
}

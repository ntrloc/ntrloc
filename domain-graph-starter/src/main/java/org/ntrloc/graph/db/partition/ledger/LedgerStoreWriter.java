package org.ntrloc.graph.db.partition.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Writing the ledger. Only the ledger/register coordinator holds this: it keeps the ledger and the
 * register consistent, so nothing else may write either (docs/ntrloc-architecture-rationalization.md §2).
 */
public interface LedgerStoreWriter {

    // Writes entries as UNCOMMITTED under transactionId. Not visible via readItemStream/
    // readLinkStream until commit(transactionId) is called. createdBy/createdDate are single
    // values for the whole batch, not per-entry -- everything appended together in one call came
    // from one mutate() request, so it shares one actor and one moment. createdDate is supplied
    // explicitly by the caller (not a DB-generated NOW()) so it can be the exact same value
    // threaded into the register row(s) this batch causes (LedgerRegisterCoordinatorImpl.prepare)
    // -- byte-for-byte identical provenance, not two independently-generated timestamps.
    void append(List<LedgerEntry> entries, UUID transactionId, UUID createdBy, OffsetDateTime createdDate);

    void commit(UUID transactionId, UUID commitId);

    // Deletes transactionId's UNCOMMITTED entries outright, rather than marking them aborted.
    void abort(UUID transactionId);
}

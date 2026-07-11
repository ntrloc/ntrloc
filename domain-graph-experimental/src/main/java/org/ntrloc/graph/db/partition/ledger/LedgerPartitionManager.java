package org.ntrloc.graph.db.partition.ledger;

import java.util.List;
import java.util.UUID;

public interface LedgerPartitionManager {

    // Writes entries as UNCOMMITTED under transactionId. Not visible via readItemStream/
    // readLinkStream until commit(transactionId) is called.
    void append(List<LedgerEntry> entries, UUID transactionId);

    void commit(UUID transactionId, UUID commitId);

    // Deletes transactionId's UNCOMMITTED entries outright, rather than marking them aborted.
    void abort(UUID transactionId);

    List<LedgerEntry> readItemStream(UUID itemId);

    List<LedgerEntry> readLinkStream(UUID linkId);

    // Returns transactionId's entries regardless of state, in append order. The caller of
    // append() may not be the same process that later calls commit()/abort() (Section 11's
    // future cross-domain door), so the transaction's entries must be re-fetchable from just
    // its id rather than assuming an in-memory list survives across that boundary.
    List<LedgerEntry> readTransaction(UUID transactionId);
}

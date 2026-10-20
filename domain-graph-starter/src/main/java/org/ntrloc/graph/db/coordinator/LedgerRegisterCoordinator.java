package org.ntrloc.graph.db.coordinator;

import org.ntrloc.graph.db.partition.ledger.LedgerEntry;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// The only component permitted to import both ledger and register. Ledger and register must
// never import each other or know about one another's tables/types.
public interface LedgerRegisterCoordinator {

    // Ledger-only: appends entries as UNCOMMITTED, plus stages the corresponding new register
    // rows (also UNCOMMITTED) for any create/update entry. Deletes stage nothing here -- they
    // have no new state to hold, so they're applied directly at commit. actorUserId/createdDate
    // are a single value for the whole batch (see LedgerPartitionManager.append's own note) --
    // threaded unchanged into both the ledger entries and the register rows they stage, so a
    // register row's created_date/updated_date is byte-for-byte identical to the ledger entry
    // that produced it, not an independently-generated, merely-close timestamp.
    void prepare(List<LedgerEntry> entries, UUID transactionId, UUID actorUserId, OffsetDateTime createdDate);

    // Flips the transaction's ledger entries and staged register rows to COMMITTED, deletes the
    // superseded old register row for any update, and applies deletes directly. Resumable from
    // just transactionId -- does not require the same in-memory entries passed to prepare.
    void commit(UUID transactionId, UUID commitId);

    // Discards the transaction's UNCOMMITTED ledger entries and staged register rows. Deletes
    // were never staged, so there's nothing to undo for them.
    void abort(UUID transactionId);
}

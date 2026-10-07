package org.ntrloc.graph.mutation;

import java.util.UUID;

// Deliberately minimal, unlike MutationResponse -- confirming a previously-prepared transaction
// doesn't have the in-memory item/link results process() had on hand; commit() itself is "resumable
// from just transactionId" (see LedgerRegisterCoordinator's own doc comment) and doesn't need them
// either. Reconstructing a full item/link result list here would mean re-deriving it from the
// ledger's stored entries, which nothing currently needs -- left for later if a caller actually
// wants it back.
public record CommitResponse(UUID transactionId) {
}

package org.ntrloc.graph.mutation;

import java.util.List;
import java.util.UUID;

// transactionId is always populated, committed or not -- callers that used commit=false need it to
// confirm later; callers that left commit at its default have no particular use for it today, but
// it costs nothing to return.
public record MutationResponse(List<ItemMutationResult> items, List<LinkMutationResult> links, UUID transactionId) {
}

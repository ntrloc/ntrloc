package org.ntrloc.graph.mutation;

import org.springframework.lang.Nullable;

import java.util.List;

// commit defaults to true (null treated as true, see MutationRequestProcessor.process) --
// preserves existing behavior for every caller built before this field existed. commit=false
// prepares the transaction (durably, in its own database transaction -- see
// LedgerRegisterCoordinatorImpl's REQUIRES_NEW) and returns its id without confirming it; a later
// call to the commit endpoint with that transactionId finishes it.
public record MutationRequest(@Nullable List<ItemMutation> items, @Nullable List<LinkMutation> links, @Nullable Boolean commit) {

    public MutationRequest(@Nullable List<ItemMutation> items, @Nullable List<LinkMutation> links) {
        this(items, links, null);
    }
}

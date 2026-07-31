package org.ntrloc.graph.db.mutation;

import java.util.List;

public record MutationResponse(List<ItemMutationResult> items, List<LinkMutationResult> links) {
}

package org.ntrloc.graph.mutation;

import java.util.List;

public record MutationResponse(List<ItemMutationResult> items, List<LinkMutationResult> links) {
}

package org.ntrloc.graph.db.mutation;

import java.util.List;

public record MutationRequest(List<ItemMutation> items, List<LinkMutation> links) {
}

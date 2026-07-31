package org.ntrloc.graph.db.mutation;

import org.springframework.lang.Nullable;

import java.util.List;

public record MutationRequest(@Nullable List<ItemMutation> items, @Nullable List<LinkMutation> links) {
}

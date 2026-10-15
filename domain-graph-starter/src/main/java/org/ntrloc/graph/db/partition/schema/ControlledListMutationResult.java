package org.ntrloc.graph.db.partition.schema;

import org.springframework.lang.Nullable;

import java.util.UUID;

// One entry per CreateControlledListMutation a batch actually processed -- refId is null unless
// that create supplied one (mirrors ItemMutationResult's own "refId is the caller's only way to
// learn the real id assigned to a refId-tagged create").
public record ControlledListMutationResult(@Nullable String refId, UUID listId) {
}

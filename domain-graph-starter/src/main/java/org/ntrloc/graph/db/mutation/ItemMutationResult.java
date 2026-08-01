package org.ntrloc.graph.db.mutation;

import java.util.UUID;

// refId is null unless this result is for a create that supplied one -- the caller's only way
// to learn the real id assigned to a refId-tagged create.
public record ItemMutationResult(String refId, UUID itemId, MutationOperation operation) {
}

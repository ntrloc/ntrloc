package org.ntrloc.graph.db.mutation;

import java.util.UUID;

public record LinkMutationResult(UUID linkId, MutationOperation operation) {
}

package org.ntrloc.graph.mutation;

import java.util.UUID;

public record LinkMutationResult(UUID linkId, MutationOperation operation) {
}

package org.ntrloc.graph.mutation;

import java.util.UUID;

public record ExistingItemReference(UUID itemId) implements ItemReference {
}

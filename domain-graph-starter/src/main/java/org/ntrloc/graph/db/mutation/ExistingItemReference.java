package org.ntrloc.graph.db.mutation;

import java.util.UUID;

public record ExistingItemReference(UUID itemId) implements ItemReference {
}

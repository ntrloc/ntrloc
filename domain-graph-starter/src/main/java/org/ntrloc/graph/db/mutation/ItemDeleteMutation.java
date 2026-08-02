package org.ntrloc.graph.db.mutation;

import java.util.UUID;

public record ItemDeleteMutation(UUID itemId) implements ItemMutation {
}

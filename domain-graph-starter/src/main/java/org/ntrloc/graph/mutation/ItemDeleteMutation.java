package org.ntrloc.graph.mutation;

import java.util.UUID;

public record ItemDeleteMutation(UUID itemId) implements ItemMutation {
}

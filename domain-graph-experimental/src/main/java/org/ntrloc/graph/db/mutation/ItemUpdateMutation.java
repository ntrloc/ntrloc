package org.ntrloc.graph.db.mutation;

import java.util.Map;
import java.util.UUID;

// properties is a diff: an absent key leaves that property unchanged, a null value clears it.
public record ItemUpdateMutation(UUID itemId, Map<String, Object> properties) implements ItemMutation {
}

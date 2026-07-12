package org.ntrloc.graph.db.mutation;

import java.util.Map;
import java.util.UUID;

// refId is optional -- a caller-assigned tag letting a link mutation in the same request
// reference this not-yet-persisted item before it has a real id (Section 7).
public record ItemCreateMutation(String refId, UUID itemTypeId, Map<String, Object> properties) implements ItemMutation {
}

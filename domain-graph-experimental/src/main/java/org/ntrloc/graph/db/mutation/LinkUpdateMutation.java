package org.ntrloc.graph.db.mutation;

import java.util.Map;
import java.util.UUID;

public record LinkUpdateMutation(UUID linkId, Map<String, Object> properties) implements LinkMutation {
}

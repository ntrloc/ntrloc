package org.ntrloc.graph.mutation;

import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.UUID;

public record LinkUpdateMutation(UUID linkId, @Nullable Map<String, Object> properties) implements LinkMutation {
}

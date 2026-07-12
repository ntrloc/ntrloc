package org.ntrloc.graph.db.mutation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LinkCreateMutation(UUID linkTypeId, List<LinkEndpointReference> endpoints,
                                  Map<String, Object> properties) implements LinkMutation {
}

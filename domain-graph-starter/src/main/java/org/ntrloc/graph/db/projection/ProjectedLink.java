package org.ntrloc.graph.db.projection;

import java.util.Map;
import java.util.UUID;

public record ProjectedLink(UUID linkId, Map<String, Object> properties, ProjectedItem item) {}

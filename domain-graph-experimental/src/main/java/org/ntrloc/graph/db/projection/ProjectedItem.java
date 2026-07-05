package org.ntrloc.graph.db.projection;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProjectedItem(UUID itemId, String itemType, Map<String, Object> properties, Map<String, List<ProjectedLink>> links) {}

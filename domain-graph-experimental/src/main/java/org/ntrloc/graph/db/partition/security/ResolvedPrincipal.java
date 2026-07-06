package org.ntrloc.graph.db.partition.security;

import java.util.Set;
import java.util.UUID;

public record ResolvedPrincipal(UUID id, String externalId, String displayName, Set<UUID> groupIds)
        implements NtrlocPrincipal {
}

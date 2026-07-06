package org.ntrloc.graph.acl;

import java.util.Set;
import java.util.UUID;

public record ResolvedPrincipal(UUID id, String externalId, String displayName, Set<UUID> groupIds)
        implements NtrlocPrincipal {
}

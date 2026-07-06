package org.ntrloc.graph.db.partition.security;

import java.util.Set;
import java.util.UUID;

public interface NtrlocPrincipal {
    UUID id();
    String externalId();
    String displayName();
    Set<UUID> groupIds();
}

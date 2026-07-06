package org.ntrloc.graph.acl;

import java.util.Set;
import java.util.UUID;

public interface NtrlocPrincipal {
    UUID id();
    String externalId();
    String displayName();
    Set<UUID> groupIds();
}

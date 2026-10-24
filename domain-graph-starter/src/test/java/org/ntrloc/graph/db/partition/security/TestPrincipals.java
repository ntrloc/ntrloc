package org.ntrloc.graph.db.partition.security;

import java.util.Set;
import java.util.UUID;

// The one test-side door through NtrlocPrincipal's package-private constructor (see that
// class's own comment on why it's restricted). Lives in this package specifically so it has
// constructor access; tests elsewhere call TestPrincipals.of(...) instead of
// `new NtrlocPrincipal(...)`, which doesn't compile outside this package.
public final class TestPrincipals {

    private TestPrincipals() {
    }

    public static NtrlocPrincipal of(UUID id, String displayName, String email, Set<UUID> groupIds, boolean isAdmin) {
        return new NtrlocPrincipal(id, displayName, email, groupIds, isAdmin);
    }
}

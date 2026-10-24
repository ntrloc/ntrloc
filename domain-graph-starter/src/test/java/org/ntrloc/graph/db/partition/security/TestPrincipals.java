package org.ntrloc.graph.db.partition.security;

import java.util.Set;
import java.util.UUID;

// The one test-side door through ResolvedPrincipal's package-private constructor (see that
// class's own comment on why it's restricted). Lives in this package specifically so it has
// constructor access; tests elsewhere call TestPrincipals.of(...) instead of
// `new ResolvedPrincipal(...)`, which no longer compiles outside this package.
public final class TestPrincipals {

    private TestPrincipals() {
    }

    public static NtrlocPrincipal of(UUID id, String displayName, String email, Set<UUID> groupIds, boolean isSuperuser) {
        return new ResolvedPrincipal(id, displayName, email, groupIds, isSuperuser);
    }
}

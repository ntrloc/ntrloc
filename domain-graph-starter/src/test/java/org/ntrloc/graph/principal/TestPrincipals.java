package org.ntrloc.graph.principal;

import org.ntrloc.graph.principal.TestPrincipals;


import java.util.Set;
import java.util.UUID;

// The one test-side door through NtrlocPrincipal's package-private constructor (see that class's
// comment). Lives in this package for the access; tests elsewhere call TestPrincipals.of(...).
public final class TestPrincipals {

    private TestPrincipals() {
    }

    public static NtrlocPrincipal of(UUID id, String displayName, String email, Set<UUID> groupIds, boolean isAdmin) {
        return new NtrlocPrincipal(id, displayName, email, groupIds, isAdmin);
    }
}

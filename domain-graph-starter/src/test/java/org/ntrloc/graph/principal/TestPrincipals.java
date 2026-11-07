package org.ntrloc.graph.principal;

import org.ntrloc.graph.principal.TestPrincipals;

import org.ntrloc.graph.principal.NtrlocPrincipal;

import java.util.Set;
import java.util.UUID;

// How tests make a principal without resolving one (production code may only get one from
// PrincipalResolverImpl -- see NtrlocPrincipal's comment).
public final class TestPrincipals {

    private TestPrincipals() {
    }

    public static NtrlocPrincipal of(UUID id, String displayName, String email, Set<UUID> groupIds, boolean isAdmin) {
        return new NtrlocPrincipal(id, displayName, email, groupIds, isAdmin);
    }
}

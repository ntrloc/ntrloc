package org.ntrloc.graph.db.partition.security;

import java.util.Set;
import java.util.UUID;

// Package-private, not public: a record's canonical constructor can never be less visible than
// the record class itself (JLS), so the only way to restrict *construction* to this package is
// to restrict the whole type to this package. Nothing outside org.ntrloc.graph.db.partition.security
// needs the concrete type anyway -- every caller holds an NtrlocPrincipal, which IS public.
// Everything else goes through PrincipalResolver (or, in tests, TestPrincipals). See
// NtrlocPrincipal's own comment and docs/ntrloc-principal-enforcement-plan.md, Part A.
record ResolvedPrincipal(UUID id, String displayName, String email, Set<UUID> groupIds, boolean isSuperuser)
        implements NtrlocPrincipal {
}

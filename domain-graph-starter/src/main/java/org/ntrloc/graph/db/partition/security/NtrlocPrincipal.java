package org.ntrloc.graph.db.partition.security;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

// Extends java.security.Principal (getName() defaulted to email()) so any Authentication
// whose own principal object already IS an NtrlocPrincipal (NtrlocUserDetails today, for local
// credential logins) behaves sensibly under Spring Security's own Authentication.getName()
// convention too, not just when unwrapped through PrincipalResolver.
//
// Sealed to exactly these two implementers, and both have a package-private canonical
// constructor -- an NtrlocPrincipal can only ever come from code inside this package (in
// practice, from PrincipalResolver/LocalUserDetailsService/PersonalAccessTokenService, all of
// which resolve against security_user rather than fabricating identity). Nothing outside this
// package can construct one, so a caller holding a non-null NtrlocPrincipal can trust it came
// from a real resolution path, not an ad-hoc `new ResolvedPrincipal(...)` anywhere in the
// codebase -- see docs/ntrloc-principal-enforcement-plan.md, Part A, for why this matters.
public sealed interface NtrlocPrincipal extends Principal permits ResolvedPrincipal, NtrlocUserDetails {
    UUID id();
    String displayName();
    String email();
    Set<UUID> groupIds();

    /** Superusers bypass marker authorization entirely — not constrained by any policy. */
    boolean isSuperuser();

    // @JsonIgnore: Jackson reads getName() as a bean getter and would emit a redundant "name"
    // field alongside email(), which is what it delegates to.
    @JsonIgnore
    @Override
    default String getName() {
        return email();
    }
}

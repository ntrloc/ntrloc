package org.ntrloc.graph.db.partition.security;

import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// The authenticated user, as every authorization check sees them. The constructor is
// package-private: an NtrlocPrincipal can only come from PrincipalResolverImpl, which resolves it
// against security_user rather than fabricating identity, so a caller holding a non-null
// NtrlocPrincipal can trust it came from a real resolution path (tests use TestPrincipals) -- see
// docs/ntrloc-principal-enforcement-plan.md, Part A, for why this matters. A class rather than a
// record because a record's constructor can't be less visible than the record itself.
//
// Implements java.security.Principal (getName() is the email) so an Authentication whose principal
// is an NtrlocPrincipal behaves sensibly under Spring Security's own getName() convention too, and
// Serializable because a login session holds it.
public final class NtrlocPrincipal implements Principal, Serializable {

    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String displayName;
    private final String email;
    private final Set<UUID> groupIds;
    private final boolean isAdmin;

    NtrlocPrincipal(UUID id, String displayName, String email, Set<UUID> groupIds, boolean isAdmin) {
        this.id = id;
        this.displayName = displayName;
        this.email = email;
        this.groupIds = Set.copyOf(groupIds);
        this.isAdmin = isAdmin;
    }

    public UUID id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String email() {
        return email;
    }

    public Set<UUID> groupIds() {
        return groupIds;
    }

    /** Admins bypass marker authorization entirely -- not constrained by any policy. */
    public boolean isAdmin() {
        return isAdmin;
    }

    @Override
    public String getName() {
        return email;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NtrlocPrincipal other
                && id.equals(other.id)
                && Objects.equals(displayName, other.displayName)
                && Objects.equals(email, other.email)
                && groupIds.equals(other.groupIds)
                && isAdmin == other.isAdmin;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, email, groupIds, isAdmin);
    }

    @Override
    public String toString() {
        return "NtrlocPrincipal[id=" + id + ", email=" + email + ", isAdmin=" + isAdmin + "]";
    }
}

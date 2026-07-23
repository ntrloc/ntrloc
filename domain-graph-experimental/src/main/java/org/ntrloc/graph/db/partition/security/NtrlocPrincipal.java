package org.ntrloc.graph.db.partition.security;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

// Extends java.security.Principal (getName() defaulted to externalId()) so any Authentication
// whose own principal object already IS an NtrlocPrincipal (NtrlocUserDetails today, for local
// credential logins) behaves sensibly under Spring Security's own Authentication.getName()
// convention too, not just when unwrapped through PrincipalResolver.
public interface NtrlocPrincipal extends Principal {
    UUID id();
    String externalId();
    String displayName();
    Set<UUID> groupIds();

    /** Superusers bypass marker authorization entirely — not constrained by any policy. */
    boolean isSuperuser();

    // @JsonIgnore: getName()'s "get"-prefixed shape reads as a classic bean-getter to Jackson
    // (record accessors like externalId() don't collide the same way -- those match Jackson's
    // separate record-component convention, not the getX one) -- without this, serializing an
    // NtrlocPrincipal (NtrlocPrincipalVariableType, storing one as a single process variable)
    // would emit a redundant "name" field that the same class can't deserialize back, since
    // there's no matching record component for it.
    @JsonIgnore
    @Override
    default String getName() {
        return externalId();
    }
}

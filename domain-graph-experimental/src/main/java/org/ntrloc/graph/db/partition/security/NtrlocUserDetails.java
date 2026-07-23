package org.ntrloc.graph.db.partition.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Combines Spring Security's own authentication contract (UserDetails: password check, roles/
// authorities) with this app's richer identity shape (NtrlocPrincipal: id, groupIds, isSuperuser,
// displayName) in one object -- LocalUserDetailsService hands Spring Security one of these, and
// because UserDetailsRepositoryReactiveAuthenticationManager sets Authentication's principal to
// the exact UserDetails instance it authenticated (confirmed against Spring Security's own
// behavior, not assumed), a locally-authenticated Authentication.getPrincipal() already IS a
// usable NtrlocPrincipal from then on -- PrincipalResolver.resolve() can return it directly
// instead of re-querying SecurityRepository on every single request just to reconstruct what was
// already known at login time.
public class NtrlocUserDetails implements UserDetails, NtrlocPrincipal {

    private final NtrlocPrincipal principal;
    private final String passwordHash;
    private final String role;
    private final boolean active;

    public NtrlocUserDetails(NtrlocPrincipal principal, String passwordHash, String role, boolean active) {
        this.principal = principal;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = active;
    }

    @Override
    public UUID id() {
        return principal.id();
    }

    @Override
    public String externalId() {
        return principal.externalId();
    }

    @Override
    public String displayName() {
        return principal.displayName();
    }

    @Override
    public Set<UUID> groupIds() {
        return principal.groupIds();
    }

    @Override
    public boolean isSuperuser() {
        return principal.isSuperuser();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return principal.externalId();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}

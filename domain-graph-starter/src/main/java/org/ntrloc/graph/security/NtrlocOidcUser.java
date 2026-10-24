package org.ntrloc.graph.security;

import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipalHolder;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.List;

// An OIDC login's principal: Spring Security requires an OidcUser here, so this is one, carrying
// the ntrloc account the login resolved to. Grants no authorities -- admin comes only from the
// NtrlocPrincipal, never from the identity provider.
final class NtrlocOidcUser extends DefaultOidcUser implements NtrlocPrincipalHolder {

    private final NtrlocPrincipal principal;

    NtrlocOidcUser(OidcUser oidcUser, NtrlocPrincipal principal) {
        super(List.of(), oidcUser.getIdToken(), oidcUser.getUserInfo(), IdTokenClaimNames.SUB);
        this.principal = principal;
    }

    @Override
    public NtrlocPrincipal ntrlocPrincipal() {
        return principal;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NtrlocOidcUser other && super.equals(other) && principal.equals(other.principal);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + principal.hashCode();
    }
}

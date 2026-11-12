package org.ntrloc.graph.principal;

import org.ntrloc.graph.db.partition.security.PersonalAccessTokenService;
import org.ntrloc.graph.db.partition.security.repository.UserAccessCache;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

// Principal Resolver (docs/ntrloc-architecture-rationalization.md §4, §7): the identity component, and the
// only code that builds an NtrlocPrincipal (its constructor is package-private for exactly this reason).
// Callers depend on whichever of the two interfaces fits them: ExternalPrincipalResolver for requests,
// InternalPrincipalResolver for stored user references.
//
// Every principal it hands out is current (docs/ntrloc-drafts-plan.md step 2.3): name, email, groups and
// admin flag come from UserAccessCache, so a change applies on the user's next request, not their next
// sign-in, and a deleted user's session stops resolving at all. The cache makes that a map lookup.
@Component
class PrincipalResolverImpl implements ExternalPrincipalResolver, InternalPrincipalResolver {

    private final UserStore store;
    private final UserAccessCache access;

    PrincipalResolverImpl(UserStore store, UserAccessCache access) {
        this.store = store;
        this.access = access;
    }

    @Override
    public Optional<NtrlocPrincipal> byEmail(String email) {
        return store.findUserByEmail(email).flatMap(user -> current(user.id()));
    }

    @Override
    public Optional<NtrlocPrincipal> byToken(String rawToken) {
        return store.findUserByValidTokenHash(PersonalAccessTokenService.hash(rawToken)).flatMap(user -> current(user.id()));
    }

    @Override
    public Optional<NtrlocPrincipal> byId(UUID id) {
        return current(id);
    }

    // Local, LDAP and PAT logins carry their NtrlocPrincipal as the Authentication's principal.
    // OIDC logins, whose principal must be an OidcUser, carry it via NtrlocPrincipalHolder. Either way, what
    // the login stored only says who signed in; everything else is read as it is now.
    @Override
    public Optional<NtrlocPrincipal> fromAuthentication(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (auth.getPrincipal() instanceof NtrlocPrincipal principal) {
            return current(principal.id());
        }
        if (auth.getPrincipal() instanceof NtrlocPrincipalHolder holder) {
            return current(holder.ntrlocPrincipal().id());
        }
        return Optional.empty();
    }

    @Override
    public NtrlocPrincipal resolve(Authentication auth) {
        return fromAuthentication(auth).orElseThrow(PrincipalResolverImpl::notAuthenticated);
    }

    private static ResponseStatusException notAuthenticated() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
    }

    // The user as they are now; empty once they've been deleted.
    private Optional<NtrlocPrincipal> current(UUID id) {
        return access.of(id).map(a -> new NtrlocPrincipal(id, a.displayName(), a.email(), a.groupIds(), a.isAdmin()));
    }
}

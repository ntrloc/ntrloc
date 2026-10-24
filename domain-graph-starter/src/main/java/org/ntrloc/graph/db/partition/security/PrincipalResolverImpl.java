package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

// The only code that builds an NtrlocPrincipal (NtrlocPrincipal's constructor is package-private
// for exactly this reason). Callers depend on whichever of the two interfaces fits them:
// ExternalPrincipalResolver for requests, InternalPrincipalResolver for stored user references.
@Component
class PrincipalResolverImpl implements ExternalPrincipalResolver, InternalPrincipalResolver {

    private final SecurityRepository repo;

    PrincipalResolverImpl(SecurityRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<NtrlocPrincipal> byEmail(String email) {
        return repo.findUserByEmail(email).map(this::toPrincipal);
    }

    @Override
    public Optional<NtrlocPrincipal> byToken(String rawToken) {
        return repo.findUserByValidTokenHash(PersonalAccessTokenService.hash(rawToken)).map(this::toPrincipal);
    }

    @Override
    public Optional<NtrlocPrincipal> byId(UUID id) {
        return repo.findUserById(id).map(this::toPrincipal);
    }

    // Local logins and PATs already carry their NtrlocPrincipal as the Authentication's principal.
    // LDAP and OAuth logins don't yet, so they fall back to an email lookup on the authenticated name.
    @Override
    public NtrlocPrincipal resolve(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw notAuthenticated();
        }
        if (auth.getPrincipal() instanceof NtrlocPrincipal principal) {
            return principal;
        }
        return byEmail(auth.getName()).orElseThrow(PrincipalResolverImpl::notAuthenticated);
    }

    private static ResponseStatusException notAuthenticated() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
    }

    private NtrlocPrincipal toPrincipal(SecurityRepository.UserRow user) {
        return new NtrlocPrincipal(user.id(), user.displayName(), user.email(),
                repo.getUserGroupIdsForUser(user.id()), user.isAdmin());
    }
}

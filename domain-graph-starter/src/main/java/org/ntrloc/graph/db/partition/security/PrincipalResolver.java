package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Not @ProcessAccessible today: no shipped process script reads the "principal" variable yet. If
// one ever does, it needs resolveById, not a bare execution.getVariable("principal") cast -- that
// variable holds only a UUID string (security_user.id), not a serialized NtrlocPrincipal.
@Component
public class PrincipalResolver {

    // The Flowable process variable name a long-lived user reference is stored under -- set by
    // ProcessAdminController.startProcessInstance for an HTTP-triggered run, or by
    // ProcessRunAsUserListener for a process with no HTTP caller (a timer, e.g.) that declares
    // flowable:runAsUser. Stores a bare UUID string (security_user.id), not a serialized principal
    // object -- see resolveById's own comment on why a stable reference beats a cached snapshot.
    public static final String PRINCIPAL_VARIABLE = "principal";

    private final SecurityRepository repo;

    public PrincipalResolver(SecurityRepository repo) {
        this.repo = repo;
    }

    /**
     * Resolves the request's authenticated user. SecurityConfig's filter chain requires
     * authentication for every non-open path, so an Authentication is present by the time a
     * controller calls this; the 401 is a safety net, never an expected path.
     */
    public NtrlocPrincipal resolve(Authentication authentication) {
        return resolveFromAuthenticatedSession(authentication)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
    }

    private Optional<NtrlocPrincipal> resolveFromAuthenticatedSession(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        // Local-credential and PAT logins already carry a real NtrlocPrincipal as the
        // Authentication's own principal object (NtrlocUserDetails / PersonalAccessTokenService,
        // respectively) -- no need to re-query SecurityRepository for what's already known. Other
        // logins (LDAP, OAuth) fall through to an email lookup on the authenticated name.
        if (auth.getPrincipal() instanceof NtrlocPrincipal principal) {
            return Optional.of(principal);
        }
        return repo.findUserByEmail(auth.getName()).map(this::toPrincipal);
    }

    // Resolves fresh from the stable internal id every time it's called -- deliberately not cached.
    // Backs every stored user reference (PRINCIPAL_VARIABLE, runAsUser, task assignees): a reader
    // always gets the user's *current* groups/superuser status, not a snapshot. If the user has
    // since been deleted, this returns empty rather than a stale, possibly more-privileged ghost.
    public Optional<NtrlocPrincipal> resolveById(UUID id) {
        return repo.findUserById(id).map(this::toPrincipal);
    }

    private NtrlocPrincipal toPrincipal(SecurityRepository.UserRow user) {
        Set<UUID> groupIds = repo.getUserGroupIdsForUser(user.id());
        return new ResolvedPrincipal(user.id(), user.displayName(), user.email(), groupIds, user.isSuperuser());
    }
}

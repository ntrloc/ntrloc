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

// Not @ProcessAccessible today: no shipped process script actually reads the "principal" variable
// yet (confirmed by search -- both writers below are the only two references in the codebase). If
// one ever does, it needs resolveById, not a bare execution.getVariable("principal") cast -- that
// variable now holds only a UUID string (security_user.id), not a serialized NtrlocPrincipal (see
// PRINCIPAL_VARIABLE's own comment on why: a cached snapshot goes stale over a long-running
// process, a stable id re-resolved on read never does). Whether PrincipalResolver itself becomes
// @ProcessAccessible, or some other already-accessible bean exposes a narrower wrapper, is an open
// question for whenever a real script actually needs this.
// resolveByExternalId below is for a different, still-internal caller: ProcessRunAsUserListener,
// resolving a process's declared flowable:runAsUser (an externalId, from BPMN XML, not a live
// Authentication) when a process starts with no HTTP caller to take a principal from at all.
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
        // respectively) -- no need to re-query SecurityRepository for what's already known. LDAP
        // logins don't (Spring LDAP's own UserDetails shape, not one of ours), so those still fall
        // through to the lookup below; correct, just not the fast path.
        if (auth.getPrincipal() instanceof NtrlocPrincipal principal) {
            return Optional.of(principal);
        }
        return repo.findUserByExternalId(auth.getName()).map(this::toPrincipal);
    }

    // No HTTP-flavored exception here (unlike resolve) -- there's no request to attach
    // a 401 to when this runs from an engine event listener at process-start time. Returns empty
    // for "unknown", leaving the caller to decide what an unresolvable declared user means for it.
    public Optional<NtrlocPrincipal> resolveByExternalId(String externalId) {
        return repo.findUserByExternalId(externalId).map(this::toPrincipal);
    }

    // Resolves fresh from the stable internal id every time it's called -- deliberately not cached
    // anywhere beyond this one lookup. Backs PRINCIPAL_VARIABLE: a process instance that outlives
    // its triggering HTTP session stores only this UUID, never a snapshotted NtrlocPrincipal, so a
    // script reading it always gets the user's *current* groups/superuser status, not whatever was
    // true when the process started. If the user has since been deleted, this returns empty --
    // correctly failing the read rather than handing back a stale, possibly more-privileged ghost.
    public Optional<NtrlocPrincipal> resolveById(UUID id) {
        return repo.findUserById(id).map(this::toPrincipal);
    }

    private NtrlocPrincipal toPrincipal(SecurityRepository.UserRow user) {
        Set<UUID> groupIds = repo.getUserGroupIdsForUser(user.id());
        return new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), user.email(), groupIds, user.isSuperuser());
    }
}

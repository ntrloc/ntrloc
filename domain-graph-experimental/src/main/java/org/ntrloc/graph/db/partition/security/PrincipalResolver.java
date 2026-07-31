package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Not @ProcessAccessible: nothing in this class needs to be called from a process script/
// delegateExpression. ProcessAdminController.startProcessInstance resolves the caller's principal
// once, up front, and stores the whole NtrlocPrincipal as a process variable (see
// NtrlocPrincipalVariableType) -- a script recovers it via a plain
// execution.getVariable("principal"), not by asking this bean to look anything up again.
// resolveByExternalId below is for a different, still-internal caller: ProcessRunAsUserListener,
// resolving a process's declared flowable:runAsUser (an externalId, from BPMN XML, not a live
// Authentication) when a process starts with no HTTP caller to take a principal from at all.
@Component
public class PrincipalResolver {

    private final SecurityRepository repo;
    private final SecurityProperties properties;

    public PrincipalResolver(SecurityRepository repo, SecurityProperties properties) {
        this.repo = repo;
        this.properties = properties;
    }

    /**
     * Resolves the real authenticated session first, falling back to the header/query-param
     * stand-in only when no real session exists. This order is what keeps the stand-in safe:
     * SecurityConfig's filter chain already requires authentication for every request once
     * graph.security.enabled=true, so a real Authentication is guaranteed present by the time
     * this runs in that mode — the stand-in can only ever be reached when security is disabled
     * (permissive/test mode), never as a way to spoof a principal past real authentication.
     *
     * The Authentication is passed in (resolved by Spring as a controller method parameter,
     * e.g. via a bare Authentication/@AuthenticationPrincipal parameter) rather than pulled
     * from ReactiveSecurityContextHolder here — blocking on that Mono trips Reactor's
     * non-blocking-thread guard inside the Security filter chain's reactive context.
     */
    public NtrlocPrincipal resolve(ServerHttpRequest request, Authentication authentication) {
        return resolveFromAuthenticatedSession(authentication)
                .orElseGet(() -> resolveFromStandIn(request));
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

    private NtrlocPrincipal resolveFromStandIn(ServerHttpRequest request) {
        String externalId = request.getHeaders().getFirst(properties.getHeaderName());
        if (externalId == null || externalId.isBlank()) {
            List<String> params = request.getQueryParams().get(properties.getParamName());
            externalId = (params == null || params.isEmpty()) ? null : params.get(0);
        }
        if (externalId == null || externalId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Missing stand-in principal: supply '%s' header or '%s' query param"
                            .formatted(properties.getHeaderName(), properties.getParamName()));
        }
        String resolvedId = externalId;
        var user = repo.findUserByExternalId(resolvedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Unknown stand-in principal: " + resolvedId));
        return toPrincipal(user);
    }

    // No HTTP-flavored exception here (unlike resolveFromStandIn) -- there's no request to attach
    // a 401 to when this runs from an engine event listener at process-start time. Returns empty
    // for "unknown", leaving the caller to decide what an unresolvable declared user means for it.
    public Optional<NtrlocPrincipal> resolveByExternalId(String externalId) {
        return repo.findUserByExternalId(externalId).map(this::toPrincipal);
    }

    private NtrlocPrincipal toPrincipal(SecurityRepository.UserRow user) {
        Set<UUID> groupIds = repo.getGroupIdsForUser(user.id());
        return new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), user.email(), groupIds, user.isSuperuser());
    }
}

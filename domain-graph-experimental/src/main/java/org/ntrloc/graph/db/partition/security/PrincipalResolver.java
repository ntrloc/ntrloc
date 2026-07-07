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
     * ntrloc.security.enabled=true, so a real Authentication is guaranteed present by the time
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

    private NtrlocPrincipal toPrincipal(SecurityRepository.UserRow user) {
        Set<UUID> groupIds = repo.getGroupIdsForUser(user.id());
        return new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), groupIds, user.isSuperuser());
    }
}

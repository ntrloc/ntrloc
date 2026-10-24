package org.ntrloc.graph.db.partition.security;

import org.springframework.security.core.Authentication;

import java.util.Optional;

/**
 * Resolves the principal behind an incoming request: someone who has just proven who they are,
 * by password, personal access token, or an external identity provider. See
 * {@link InternalPrincipalResolver} for acting as a user the system has stored a reference to.
 */
public interface ExternalPrincipalResolver {

    /** The user with this email (case-insensitive) -- the identity a local or IdP login proved. */
    Optional<NtrlocPrincipal> byEmail(String email);

    /** The owner of this raw personal access token, if the token is valid and hasn't expired. */
    Optional<NtrlocPrincipal> byToken(String rawToken);

    /**
     * The principal an authenticated request carries; empty for an anonymous or unauthenticated
     * one. Never a lookup: every login resolves its principal once, when it happens.
     */
    Optional<NtrlocPrincipal> fromAuthentication(Authentication authentication);

    /**
     * The principal of an authenticated request. SecurityConfig's filter chain requires
     * authentication for every non-open path, so this throws a 401 only as a safety net.
     */
    NtrlocPrincipal resolve(Authentication authentication);
}

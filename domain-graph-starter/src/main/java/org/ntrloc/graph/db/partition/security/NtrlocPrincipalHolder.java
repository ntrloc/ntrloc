package org.ntrloc.graph.db.partition.security;

/**
 * Implemented by an Authentication's principal object when Spring Security dictates its type
 * (an OIDC login's principal must be an OidcUser), so it carries the user's resolved
 * NtrlocPrincipal alongside instead of being one.
 */
public interface NtrlocPrincipalHolder {

    NtrlocPrincipal ntrlocPrincipal();
}

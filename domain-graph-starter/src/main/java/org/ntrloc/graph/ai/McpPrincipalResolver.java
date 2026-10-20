package org.ntrloc.graph.ai;

import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.PersonalAccessTokenService;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

// Shared by all four MCP tool services (ProjectionService, MutationService, SchemaService,
// ProcessService) -- see docs/ntrloc-principal-enforcement-plan.md, Part D, for why this used to
// be copy-pasted across the first two and why that stopped being acceptable once a third and
// fourth tool needed the exact same logic: there is no Spring mechanism that hands an MCP tool
// method its caller's identity automatically the way a servlet filter chain does for a controller
// (method security guards a resolved principal, it doesn't produce one), so every tool has to
// resolve it this same manual way -- the Authorization header McpTransportContextConfig captured
// off the underlying HTTP request, re-resolved via PersonalAccessTokenService.authenticate -- and
// every tool must reject an unresolvable one the same way. There is no legitimate anonymous MCP
// call (Part C): requirePrincipal is the one method every @McpTool method on every one of the
// four services calls first, even the two (SchemaService, ProcessService) whose own responses
// aren't filtered by identity today -- rejecting an unauthenticated caller is a floor, not
// conditional on whether this particular tool's output happens to be scoped by who's asking.
@Component
public class McpPrincipalResolver {

    private static final String BEARER_PREFIX = "Bearer ";

    private final PersonalAccessTokenService personalAccessTokenService;

    public McpPrincipalResolver(PersonalAccessTokenService personalAccessTokenService) {
        this.personalAccessTokenService = personalAccessTokenService;
    }

    public NtrlocPrincipal requirePrincipal(McpSyncRequestContext requestContext) {
        NtrlocPrincipal principal = resolvePrincipal(requestContext);
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unable to resolve an authenticated principal for this MCP call");
        }
        return principal;
    }

    // MutationRequest.authenticate is a blocking JDBC call, safe here since Spring AI invokes a
    // "Sync" @McpTool method's body off the event loop, unlike the transport-context extraction
    // itself (see McpTransportContextConfig's own comment on why the raw header, not a resolved
    // principal, is what gets captured there).
    private NtrlocPrincipal resolvePrincipal(McpSyncRequestContext requestContext) {
        Object header = requestContext.transportContext().get(McpTransportContextConfig.AUTHORIZATION_TRANSPORT_KEY);
        if (!(header instanceof String authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String rawToken = authorization.substring(BEARER_PREFIX.length());
        return personalAccessTokenService.authenticate(rawToken).orElse(null);
    }
}

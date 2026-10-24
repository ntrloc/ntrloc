package org.ntrloc.graph.ai;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.PersonalAccessTokenService;
import org.ntrloc.graph.db.partition.security.TestPrincipals;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.projection.CollectionProjectionSpec;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Proves the whole MCP-tool-dispatch path for this read tool, end to end -- something no test
// exercised at all before Part C of docs/ntrloc-principal-enforcement-plan.md (zero tests existed
// for any of the four MCP tool services). Called as a real autowired Spring bean (so its own AOP
// proxy, and every proxy of every bean it calls through -- EntityManager, PermissionService --
// is genuinely in play, not bypassed via direct instantiation), with a mocked
// McpSyncRequestContext standing in for the one piece that would otherwise need real MCP wire
// protocol machinery to construct; everything downstream of transportContext() is the real,
// production call chain.
class ProjectionServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProjectionService projectionService;

    @Autowired
    private PersonalAccessTokenService tokenService;

    @Autowired
    private SecurityRepository securityRepo;

    @Test
    void projectItems_withNoAuthorizationHeader_isRejected() {
        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        when(requestContext.transportContext()).thenReturn(McpTransportContext.EMPTY);

        assertThatThrownBy(() -> projectionService.projectItems(
                new CollectionProjectionSpec("CoordinatorTestProduct", null, null), requestContext))
                .isInstanceOf(ResponseStatusException.class);
    }

    // The property that matters here: a real, authenticated MCP call makes it all the way through
    // EntityManager.project's own @PreAuthorize guard (added in Part C) without being incorrectly
    // rejected -- proving the new guard doesn't break the one legitimate path into this tool,
    // not just that an anonymous one gets refused (already covered by the test above and by this
    // method's own pre-existing null-check, neither of which touch EntityManager at all).
    @Test
    void projectItems_withAValidBearerToken_succeeds() {
        var user = securityRepo.createUser("MCP Projection Test User", "mcp-projection-user-" + UUID.randomUUID() + "@example.com", true);
        var principal = TestPrincipals.of(user.id(), user.displayName(), user.email(), Set.of(), true);
        var issued = tokenService.issue(principal, "projection-test-token", null);

        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        when(requestContext.transportContext())
                .thenReturn(McpTransportContext.create(Map.of("authorization", "Bearer " + issued.rawToken())));

        var result = projectionService.projectItems(
                new CollectionProjectionSpec("CoordinatorTestProduct", null, null), requestContext);

        assertThat(result).isNotNull();
    }
}

package org.ntrloc.graph.ai;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.PersonalAccessTokenService;
import org.ntrloc.graph.principal.TestPrincipals;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
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

// Proves the whole MCP-tool-dispatch path for this tool, end to end -- same motivation as
// ProjectionToolsIntegrationTest (see its own comment); zero tests existed for any of the four
// MCP tool services before Part C/D of docs/ntrloc-principal-enforcement-plan.md. Unlike
// Projection/Mutation, getSchema's response isn't filtered by the resolved principal at all (see
// SchemaTools's own comment) -- what this proves is narrower and specific to that: an
// unauthenticated caller is still rejected, and an authenticated one still gets the schema back,
// even though the principal itself does nothing beyond existing.
class SchemaToolsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SchemaTools schemaTools;

    @Autowired
    private PersonalAccessTokenService tokenService;

    @Autowired
    private UserStore userStore;

    @Test
    void getSchema_withNoAuthorizationHeader_isRejected() {
        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        when(requestContext.transportContext()).thenReturn(McpTransportContext.EMPTY);

        assertThatThrownBy(() -> schemaTools.getSchema(requestContext))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void getSchema_withAValidBearerToken_succeeds() {
        var user = userStore.createUser("MCP Schema Test User", "mcp-schema-user-" + UUID.randomUUID() + "@example.com", true);
        var principal = TestPrincipals.of(user.id(), user.displayName(), user.email(), Set.of(), true);
        var issued = tokenService.issue(principal, "schema-test-token", null);

        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        when(requestContext.transportContext())
                .thenReturn(McpTransportContext.create(Map.of("authorization", "Bearer " + issued.rawToken())));

        var result = schemaTools.getSchema(requestContext);

        assertThat(result).isNotNull();
        assertThat(result.items()).isNotNull();
    }
}

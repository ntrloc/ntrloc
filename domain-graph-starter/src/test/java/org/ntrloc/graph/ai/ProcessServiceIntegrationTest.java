package org.ntrloc.graph.ai;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.PersonalAccessTokenService;
import org.ntrloc.graph.db.partition.security.TestPrincipals;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
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
// ProjectionServiceIntegrationTest and SchemaServiceIntegrationTest (see their own comments);
// zero tests existed for any of the four MCP tool services before Part C/D of
// docs/ntrloc-principal-enforcement-plan.md. Covers only the floor Part D actually added
// (reject an unresolvable caller) via listProcesses, one representative method -- not the
// separate, still-open gap (task operations bypassing ntrloc's own marker/permission system
// entirely) that ProcessService's own class comment flags as deliberately out of scope here.
class ProcessServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProcessService processService;

    @Autowired
    private PersonalAccessTokenService tokenService;

    @Autowired
    private SecurityRepository securityRepo;

    @Test
    void listProcesses_withNoAuthorizationHeader_isRejected() {
        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        when(requestContext.transportContext()).thenReturn(McpTransportContext.EMPTY);

        assertThatThrownBy(() -> processService.listProcesses(requestContext))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void listProcesses_withAValidBearerToken_succeeds() {
        var user = securityRepo.createUser("MCP Process Test User", "mcp-process-user-" + UUID.randomUUID() + "@example.com", true);
        var principal = TestPrincipals.of(user.id(), user.displayName(), user.email(), Set.of(), true);
        var issued = tokenService.issue(principal, "process-test-token", null);

        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        when(requestContext.transportContext())
                .thenReturn(McpTransportContext.create(Map.of("authorization", "Bearer " + issued.rawToken())));

        var result = processService.listProcesses(requestContext);

        assertThat(result).isNotNull();
    }
}

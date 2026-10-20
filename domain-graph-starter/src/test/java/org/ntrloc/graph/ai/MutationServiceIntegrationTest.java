package org.ntrloc.graph.ai;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.PersonalAccessTokenService;
import org.ntrloc.graph.db.partition.security.TestPrincipals;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.mutation.ItemCreateMutation;
import org.ntrloc.graph.mutation.MutationRequest;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authorization.AuthorizationDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Proves the whole MCP-tool-dispatch path for this write tool, end to end -- same motivation as
// ProjectionServiceIntegrationTest (see its own comment); zero tests existed for any of the four
// MCP tool services before Part C of docs/ntrloc-principal-enforcement-plan.md. Called as a real
// autowired Spring bean, same reasoning as that class.
class MutationServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MutationService mutationService;

    @Autowired
    private PersonalAccessTokenService tokenService;

    @Autowired
    private SecurityRepository securityRepo;

    // Unlike ProjectionService (which has always refused an unresolvable caller), this used to be
    // the one real gap Part C found: executeMutation would resolve a null principal and pass it
    // straight into entityManager.mutate(...) with no check of its own, relying on mutate()'s own
    // (then-tolerant) handling. mutate() now demands a real principal via @PreAuthorize, so this
    // either needed its own explicit guard (added) or would otherwise surface as an
    // AuthorizationDeniedException from deep inside EntityManager instead of a clean 401 at the
    // tool boundary -- asserting ResponseStatusException here is what proves the explicit guard
    // added to MutationService itself is the one actually firing, not just mutate()'s.
    @Test
    void executeMutation_withNoAuthorizationHeader_isRejected() {
        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        when(requestContext.transportContext()).thenReturn(McpTransportContext.EMPTY);

        assertThatThrownBy(() -> mutationService.executeMutation(
                new MutationRequest(List.of(), List.of()), requestContext))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .isNotInstanceOf(AuthorizationDeniedException.class);
    }

    // The property that matters here: a real, authenticated MCP call makes it all the way through
    // EntityManager.mutate's own @PreAuthorize guard (added in Part C) without being incorrectly
    // rejected -- proving the new guard doesn't break the one legitimate path into this tool.
    @Test
    void executeMutation_withAValidBearerToken_succeeds() {
        var user = securityRepo.createUser("mcp-mutation-user-" + UUID.randomUUID(), "MCP Mutation Test User", null, true);
        var principal = TestPrincipals.of(user.id(), user.externalId(), user.displayName(), null, Set.of(), true);
        var issued = tokenService.issue(principal, "mutation-test-token", null);

        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        when(requestContext.transportContext())
                .thenReturn(McpTransportContext.create(Map.of("authorization", "Bearer " + issued.rawToken())));

        var response = mutationService.executeMutation(
                new MutationRequest(List.of(new ItemCreateMutation(null, "CoordinatorTestProduct", Map.of())), List.of()),
                requestContext);

        assertThat(response.items()).hasSize(1);
    }
}

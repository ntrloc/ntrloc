package org.ntrloc.graph.db;

import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.ntrloc.graph.db.projection.CollectionProjectionSpec;
import org.ntrloc.graph.db.projection.ProjectionResult;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/entity")
public class EntityController {

    private final EntityManager entityManager;
    private final PrincipalResolver principalResolver;

    public EntityController(EntityManager entityManager, PrincipalResolver principalResolver) {
        this.entityManager = entityManager;
        this.principalResolver = principalResolver;
    }

    @PostMapping("/projection")
    ResponseEntity<ProjectionResult> project(@RequestBody CollectionProjectionSpec spec, ServerHttpRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(request, authentication);
        String binaryBaseUrl = extractBaseUrl(request);
        return ResponseEntity.ok(entityManager.project(spec, binaryBaseUrl, principal));
    }

    // Begin the item's participation in a state machine (enters the START target state).
    @PostMapping("/{itemId}/state-machines/{stateMachineName}/start")
    ResponseEntity<Void> startStateMachine(@PathVariable UUID itemId, @PathVariable String stateMachineName,
                                            ServerHttpRequest request, Authentication authentication) {
        entityManager.startStateMachine(itemId, stateMachineName, principalResolver.resolve(request, authentication));
        return ResponseEntity.noContent().build();
    }

    // Advance an active state machine along one outgoing transition (by id).
    @PostMapping("/{itemId}/state-machines/{stateMachineName}/transitions/{transitionId}")
    ResponseEntity<Void> executeTransition(@PathVariable UUID itemId, @PathVariable String stateMachineName,
                                            @PathVariable UUID transitionId,
                                            ServerHttpRequest request, Authentication authentication) {
        entityManager.executeTransition(itemId, stateMachineName, transitionId, principalResolver.resolve(request, authentication));
        return ResponseEntity.noContent().build();
    }

    private String extractBaseUrl(ServerHttpRequest request) {
        var uri = request.getURI();
        int port = uri.getPort();
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }
}

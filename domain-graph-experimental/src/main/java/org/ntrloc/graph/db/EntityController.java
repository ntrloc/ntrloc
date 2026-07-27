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

    // Minimal, direct register write -- see EntityManager.setItemState's own comment for why this
    // is deliberately separate from /api/mutation's ledger-backed pipeline. Exists only so
    // current-state querying/faceting has real test data before real transition execution exists.
    @PostMapping("/{itemId}/state")
    ResponseEntity<?> setState(@PathVariable UUID itemId, @RequestBody SetItemStateRequest request) {
        entityManager.setItemState(itemId, request.stateMachineName(), request.stateName());
        return ResponseEntity.ok().build();
    }

    record SetItemStateRequest(String stateMachineName, String stateName) {}

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

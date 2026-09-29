package org.ntrloc.graph.projection;

import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projection")
public class ProjectionController {

    private final EntityManager entityManager;
    private final PrincipalResolver principalResolver;

    public ProjectionController(EntityManager entityManager, PrincipalResolver principalResolver) {
        this.entityManager = entityManager;
        this.principalResolver = principalResolver;
    }

    @PostMapping("/collection")
    ResponseEntity<ProjectionResult> collection(@RequestBody CollectionProjectionSpec spec, ServerHttpRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(request, authentication);
        String binaryBaseUrl = extractBaseUrl(request);
        return ResponseEntity.ok(entityManager.project(spec, binaryBaseUrl, principal));
    }

    @PostMapping("/item")
    ResponseEntity<ProjectedItem> item(@RequestBody ItemProjectionSpec spec, ServerHttpRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(request, authentication);
        String binaryBaseUrl = extractBaseUrl(request);
        return entityManager.project(spec, binaryBaseUrl, principal)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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

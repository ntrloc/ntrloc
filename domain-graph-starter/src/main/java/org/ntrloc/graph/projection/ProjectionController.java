package org.ntrloc.graph.projection;

import jakarta.servlet.http.HttpServletRequest;
import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.springframework.http.ResponseEntity;
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
    ResponseEntity<ProjectionResult> collection(@RequestBody CollectionProjectionSpec spec, HttpServletRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(authentication);
        String binaryBaseUrl = extractBaseUrl(request);
        return ResponseEntity.ok(entityManager.project(spec, binaryBaseUrl, principal));
    }

    @PostMapping("/item")
    ResponseEntity<ProjectedItem> item(@RequestBody ItemProjectionSpec spec, HttpServletRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(authentication);
        String binaryBaseUrl = extractBaseUrl(request);
        return entityManager.project(spec, binaryBaseUrl, principal)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private String extractBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        if (port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }
}

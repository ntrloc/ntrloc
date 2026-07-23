package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.SchemaView;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schema")
public class SchemaController {

    private final SchemaManager schemaManager;
    private final PrincipalResolver principalResolver;

    public SchemaController(SchemaManager schemaManager, PrincipalResolver principalResolver) {
        this.schemaManager = schemaManager;
        this.principalResolver = principalResolver;
    }

    @GetMapping
    ResponseEntity<SchemaView> getSchema(ServerHttpRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(request, authentication);
        return ResponseEntity.ok(schemaManager.getSchema(principal));
    }
}

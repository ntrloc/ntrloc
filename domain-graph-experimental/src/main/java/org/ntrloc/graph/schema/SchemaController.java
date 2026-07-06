package org.ntrloc.graph.schema;

import org.ntrloc.graph.acl.PrincipalResolver;
import org.ntrloc.graph.schema.definition.view.calculated.SchemaView;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/schema")
public class SchemaController {

    private final SchemaManager schemaManager;
    private final PrincipalResolver principalResolver;

    public SchemaController(SchemaManager schemaManager, PrincipalResolver principalResolver) {
        this.schemaManager = schemaManager;
        this.principalResolver = principalResolver;
    }

    @GetMapping
    ResponseEntity<SchemaView> getSchema(ServerHttpRequest request) {
        var principal = principalResolver.resolve(request);
        return ResponseEntity.ok(schemaManager.getSchema(principal));
    }
}

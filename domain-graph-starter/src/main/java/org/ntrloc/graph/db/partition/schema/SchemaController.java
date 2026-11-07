package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.principal.ExternalPrincipalResolver;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.SchemaView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schema")
public class SchemaController {

    private final SchemaService schemaService;
    private final ExternalPrincipalResolver principalResolver;

    public SchemaController(SchemaService schemaService, ExternalPrincipalResolver principalResolver) {
        this.schemaService = schemaService;
        this.principalResolver = principalResolver;
    }

    @GetMapping
    ResponseEntity<SchemaView> getSchema(Authentication authentication) {
        var principal = principalResolver.resolve(authentication);
        return ResponseEntity.ok(schemaService.getSchema(principal));
    }
}

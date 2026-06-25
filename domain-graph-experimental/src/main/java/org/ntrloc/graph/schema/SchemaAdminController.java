package org.ntrloc.graph.schema;

import org.ntrloc.graph.schema.definition.view.admin.AdminSchemaView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/schema")
public class SchemaAdminController {

    private final SchemaManager schemaManager;

    public SchemaAdminController(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }

    @GetMapping
    ResponseEntity<AdminSchemaView> getAdminSchema() {
        return ResponseEntity.ok(schemaManager.getAdminSchema());
    }

}

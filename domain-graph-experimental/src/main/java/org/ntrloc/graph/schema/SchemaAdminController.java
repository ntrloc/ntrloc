package org.ntrloc.graph.schema;

import org.ntrloc.graph.schema.model.admin.AdminSchemaModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/schema/admin")
public class SchemaAdminController {

    private final SchemaManager schemaManager;

    public SchemaAdminController(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }

    @GetMapping
    ResponseEntity<AdminSchemaModel> getAdminSchema() {
        return ResponseEntity.ok(schemaManager.getAdminSchema());
    }

}

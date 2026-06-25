package org.ntrloc.graph.schema;

import org.ntrloc.graph.schema.definition.operation.SchemaOperation;
import org.ntrloc.graph.schema.definition.view.admin.AdminSchemaView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @PostMapping("/operations")
    ResponseEntity<Void> applyOperations(@RequestBody List<SchemaOperation> operations) {
        schemaManager.applyOperations(operations);
        return ResponseEntity.noContent().build();
    }

}

package org.ntrloc.graph.schema;

import org.ntrloc.graph.schema.definition.mutation.DefinitionMutation;
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

    @PostMapping("/mutations")
    ResponseEntity<Void> applyMutations(@RequestBody List<DefinitionMutation> mutations) {
        schemaManager.applyMutations(mutations);
        return ResponseEntity.noContent().build();
    }

}

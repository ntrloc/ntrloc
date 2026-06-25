package org.ntrloc.graph.schema;

import org.ntrloc.graph.schema.definition.view.calculated.SchemaView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/schema")
public class SchemaController {

    private SchemaManager schemaManager;

    public SchemaController(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }

    @GetMapping
    ResponseEntity<SchemaView> getSchema() {
        return ResponseEntity.ok(schemaManager.getSchema());
    }


}

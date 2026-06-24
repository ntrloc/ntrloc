package org.ntrloc.graph.schema;

import org.ntrloc.graph.schema.definition.PropertyType;
import org.ntrloc.graph.schema.model.PropertyTypeModel;
import org.ntrloc.graph.schema.model.SchemaModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/schema")
public class SchemaController {

    private SchemaManager schemaManager;

    public SchemaController(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }

    @GetMapping
    ResponseEntity<SchemaModel> getSchema() {
        return ResponseEntity.ok(schemaManager.getSchema());
    }

    @GetMapping("/property-types")
    ResponseEntity<List<PropertyTypeModel>> getPropertyTypes() {
        var types = Arrays.stream(PropertyType.values())
                .map(type -> new PropertyTypeModel(type, type.validCardinalities()))
                .toList();
        return ResponseEntity.ok(types);
    }

}

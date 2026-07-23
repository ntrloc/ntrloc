package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminSchemaView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/schema")
public class SchemaAdminController {

    public record ControlledListResponse(UUID listId, String name, PropertyType valueType, List<AllowedValue> values) {}

    private final SchemaManager schemaManager;
    private final ControlledListManager controlledListManager;

    public SchemaAdminController(SchemaManager schemaManager, ControlledListManager controlledListManager) {
        this.schemaManager = schemaManager;
        this.controlledListManager = controlledListManager;
    }

    @GetMapping
    ResponseEntity<AdminSchemaView> getAdminSchema() {
        return ResponseEntity.ok(schemaManager.getAdminSchema());
    }

    @GetMapping("/properties/{propertyId}/controlled-list")
    ResponseEntity<ControlledListResponse> getControlledList(@PathVariable UUID propertyId) {
        return controlledListManager.getListForProperty(propertyId)
                .map(list -> {
                    var values = controlledListManager.getValues(list.id(), list.valueType());
                    return ResponseEntity.ok(new ControlledListResponse(list.id(), list.name(), list.valueType(), values));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/mutations")
    ResponseEntity<Void> applyMutations(@RequestBody List<DefinitionMutation> mutations) {
        schemaManager.applyMutations(mutations);
        return ResponseEntity.noContent().build();
    }

}

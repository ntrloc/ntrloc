package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminSchemaView;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/schema")
public class SchemaAdminController {

    public record ControlledListResponse(UUID listId, String name, PropertyType valueType, List<AllowedValue> values) {}

    private final SchemaManager schemaManager;
    private final ControlledListManager controlledListManager;
    private final SchemaChangeBroadcaster broadcaster;

    public SchemaAdminController(SchemaManager schemaManager, ControlledListManager controlledListManager,
                                  SchemaChangeBroadcaster broadcaster) {
        this.schemaManager = schemaManager;
        this.controlledListManager = controlledListManager;
        this.broadcaster = broadcaster;
    }

    @GetMapping
    ResponseEntity<AdminSchemaView> getAdminSchema() {
        return ResponseEntity.ok(schemaManager.getAdminSchema());
    }

    // A trivial "something changed" signal, not a data channel -- every connected client just
    // re-fetches GET /api/admin/schema (see SchemaChangeBroadcaster for why).
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> schemaEvents() {
        return broadcaster.events().map(type -> ServerSentEvent.builder(type).build());
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

    public record CreateControlledListRequest(UUID propertyId, String name, List<Entry> values) {
        public record Entry(String value, String label) {}
    }

    @PutMapping("/properties/{propertyId}/controlled-list")
    ResponseEntity<ControlledListResponse> createOrReplaceControlledList(
            @PathVariable UUID propertyId, @RequestBody CreateControlledListRequest body) {
        var existing = controlledListManager.getListForProperty(propertyId);
        UUID listId;
        if (existing.isPresent()) {
            listId = existing.get().id();
            controlledListManager.replaceValues(listId, existing.get().valueType(),
                    body.values().stream()
                            .map(e -> new org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation.Entry(e.value(), e.label()))
                            .toList());
        } else {
            var list = controlledListManager.createList(body.name(), PropertyType.STRING);
            listId = list.id();
            int i = 0;
            for (var entry : body.values()) {
                controlledListManager.addValue(listId, entry.value(), entry.label(), i++);
            }
            controlledListManager.setPropertyControlledList(propertyId, listId);
        }
        var finalList = controlledListManager.getListForProperty(propertyId).orElseThrow();
        var values = controlledListManager.getValues(finalList.id(), finalList.valueType());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ControlledListResponse(finalList.id(), finalList.name(), finalList.valueType(), values));
    }

}

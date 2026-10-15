package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.NewControlledListReference;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetPropertyControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateControlledListMutation;
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

    // Fetch one list's values by list id -- the schema editor's controlled-list detail pane
    // lazy-loads values here (AdminSchemaView.controlledLists carries only name/valueCount/usedBy).
    @GetMapping("/controlled-lists/{listId}")
    ResponseEntity<ControlledListResponse> getControlledListById(@PathVariable UUID listId) {
        return controlledListManager.getListById(listId)
                .map(list -> ResponseEntity.ok(new ControlledListResponse(list.id(), list.name(), list.valueType(),
                        controlledListManager.getValues(list.id(), list.valueType()))))
                .orElse(ResponseEntity.notFound().build());
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

    // Goes through schemaManager.applyMutations like every other schema write now -- no more
    // reaching around it straight to ControlledListManager (see CreateControlledListMutation's own
    // refId and SchemaManager.applyMutations' own comment on why that bypass existed and what
    // closed the gap). The "does this property already have a list" branch stays here, since it's
    // this endpoint's own create-or-replace ergonomics, not a concern either mutation type owns --
    // it just now ends in a real mutation batch either way instead of a direct manager call.
    @PutMapping("/properties/{propertyId}/controlled-list")
    ResponseEntity<ControlledListResponse> createOrReplaceControlledList(
            @PathVariable UUID propertyId, @RequestBody CreateControlledListRequest body) {
        var existing = controlledListManager.getListForProperty(propertyId);
        if (existing.isPresent()) {
            schemaManager.applyMutations(List.of(new UpdateControlledListMutation(existing.get().id(), null,
                    body.values().stream()
                            .map(e -> new UpdateControlledListMutation.Entry(e.value(), e.label()))
                            .toList())));
        } else {
            // Create and attach in the one batch -- the second mutation references the first by its
            // own refId (NewControlledListReference) rather than this endpoint needing to look the
            // new list back up by name in between, the way two separate applyMutations calls would.
            String refId = UUID.randomUUID().toString();
            schemaManager.applyMutations(List.of(
                    new CreateControlledListMutation(refId, body.name(), PropertyType.STRING,
                            body.values().stream()
                                    .map(e -> new CreateControlledListMutation.Entry(e.value(), e.label()))
                                    .toList()),
                    new SetPropertyControlledListMutation(propertyId, new NewControlledListReference(refId))));
        }
        var finalList = controlledListManager.getListForProperty(propertyId).orElseThrow();
        var values = controlledListManager.getValues(finalList.id(), finalList.valueType());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ControlledListResponse(finalList.id(), finalList.name(), finalList.valueType(), values));
    }

}

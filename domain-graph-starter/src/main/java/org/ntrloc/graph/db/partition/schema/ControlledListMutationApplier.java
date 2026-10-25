package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.mutation.ControlledListReference;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ExistingControlledListReference;
import org.ntrloc.graph.db.partition.schema.definition.mutation.NewControlledListReference;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetPropertyControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Applies the list-centric controlled-list mutations -- controlled lists are a first-class,
// reusable schema element now, not a per-property appendage. (The property-keyed
// ReplaceControlledListMutation still exists for API back-compat and is handled inline in
// SchemaManager.) refIdToListId/results are threaded through from SchemaManager.applyMutations'
// own batch walk: a CreateControlledListMutation that supplies a refId records its real id into
// refIdToListId (so a SetPropertyControlledListMutation later in the *same* batch can resolve a
// NewControlledListReference against it) and always appends to results (so the caller can learn
// the new list's id either way, refId or not).
@Component
class ControlledListMutationApplier {

    private final ControlledListManager controlledListManager;
    private final SchemaRepository repo;

    ControlledListMutationApplier(ControlledListManager controlledListManager, SchemaRepository repo) {
        this.controlledListManager = controlledListManager;
        this.repo = repo;
    }

    boolean apply(DefinitionMutation mutation, Map<String, UUID> refIdToListId, List<ControlledListMutationResult> results) {
        switch (mutation) {
            case CreateControlledListMutation m -> {
                requireNameAvailable(m.name(), null);
                var list = controlledListManager.createList(m.name(), m.valueType());
                controlledListManager.addValues(list.id(), toValueEntries(m.values()));
                if (m.refId() != null) {
                    refIdToListId.put(m.refId(), list.id());
                }
                results.add(new ControlledListMutationResult(m.refId(), list.id()));
            }
            case UpdateControlledListMutation m -> {
                var list = controlledListManager.getListById(m.listId())
                        .orElseThrow(() -> new IllegalArgumentException("No controlled list: " + m.listId()));
                if (m.name() != null) {
                    requireNameAvailable(m.name(), list.id());
                    controlledListManager.renameList(list.id(), m.name());
                }
                if (m.values() != null) {
                    controlledListManager.replaceValues(list.id(), list.valueType(), toReplaceEntries(m.values()));
                }
            }
            case DeleteControlledListMutation m -> controlledListManager.deleteList(m.listId());
            case SetPropertyControlledListMutation m -> applySetProperty(m, refIdToListId);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void applySetProperty(SetPropertyControlledListMutation m, Map<String, UUID> refIdToListId) {
        UUID listId = resolveListId(m.list(), refIdToListId);
        if (listId == null) {
            controlledListManager.clearPropertyControlledList(m.propertyId());
            return;
        }
        AdminPropertyDefinitionView property = repo.findProperty(m.propertyId())
                .orElseThrow(() -> new IllegalArgumentException("No property: " + m.propertyId()));
        if (!ControlledListManager.SUPPORTED_TYPES.contains(property.type())) {
            throw new IllegalArgumentException(
                    "Controlled lists are not supported for property type: " + property.type());
        }
        var list = controlledListManager.getListById(listId)
                .orElseThrow(() -> new IllegalArgumentException("No controlled list: " + listId));
        if (list.valueType() != property.type()) {
            throw new IllegalArgumentException("Controlled list '" + list.name() + "' is " + list.valueType()
                    + " but property '" + property.name() + "' is " + property.type());
        }
        controlledListManager.setPropertyControlledList(m.propertyId(), listId);
    }

    // null (detach) passes straight through; a NewControlledListReference resolves against this
    // same batch's own refIdToListId (populated earlier in the walk, never later -- a mutation
    // can only reference a list created *before* it in submission order, same ordering rule
    // MutationRequestProcessor's own refId resolution already follows for items/links).
    private UUID resolveListId(ControlledListReference ref, Map<String, UUID> refIdToListId) {
        if (ref == null) {
            return null;
        }
        if (ref instanceof NewControlledListReference r) {
            UUID listId = refIdToListId.get(r.refId());
            if (listId == null) {
                throw new IllegalArgumentException("Unknown controlled list refId: " + r.refId());
            }
            return listId;
        }
        if (ref instanceof ExistingControlledListReference r) {
            return r.listId();
        }
        throw new IllegalArgumentException("Unsupported controlled list reference: " + ref.getClass().getSimpleName());
    }

    private void requireNameAvailable(String name, UUID excludeListId) {
        boolean taken = controlledListManager.getAllLists().stream()
                .anyMatch(l -> l.name().equalsIgnoreCase(name) && !l.id().equals(excludeListId));
        if (taken) {
            throw new IllegalArgumentException("A controlled list named '" + name + "' already exists");
        }
    }

    private static List<ControlledListManager.ValueEntry> toValueEntries(List<CreateControlledListMutation.Entry> entries) {
        return entries == null ? List.of()
                : entries.stream().map(e -> new ControlledListManager.ValueEntry(e.value(), e.label())).toList();
    }

    private static List<ReplaceControlledListMutation.Entry> toReplaceEntries(List<UpdateControlledListMutation.Entry> entries) {
        return entries.stream().map(e -> new ReplaceControlledListMutation.Entry(e.value(), e.label())).toList();
    }
}

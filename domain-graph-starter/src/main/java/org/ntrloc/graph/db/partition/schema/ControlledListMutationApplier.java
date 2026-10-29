package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.mutation.ControlledListReference;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ExpectControlledListMutation;
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
                // Typed as replacing them would be: a value of an INT or LONG list arrives as text.
                controlledListManager.replaceValues(list.id(), list.valueType(), m.values() == null ? List.of()
                        : m.values().stream().map(e -> new ReplaceControlledListMutation.Entry(e.value(), e.label())).toList());
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
            case ExpectControlledListMutation m -> requireUnchanged(m);
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
        controlledListManager.attachToProperty(property, listId);
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

    // Values compare in order, as text, with a blank label the same as none.
    private void requireUnchanged(ExpectControlledListMutation m) {
        var list = controlledListManager.lockList(m.listId())
                .orElseThrow(() -> new SchemaConflictException("This controlled list was deleted by someone else."));
        boolean unchanged = list.name().equals(m.name())
                && (m.values() == null || comparable(m.values()).equals(controlledListManager.getValues(list.id(), list.valueType()).stream()
                        .map(v -> new UpdateControlledListMutation.Entry(String.valueOf(v.value()), blankToNull(v.label())))
                        .toList()));
        if (!unchanged) {
            throw new SchemaConflictException("Controlled list " + list.name() + " was changed by someone else while you were editing it.");
        }
    }

    private static List<UpdateControlledListMutation.Entry> comparable(List<UpdateControlledListMutation.Entry> entries) {
        return entries.stream().map(e -> new UpdateControlledListMutation.Entry(e.value(), blankToNull(e.label()))).toList();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private void requireNameAvailable(String name, UUID excludeListId) {
        boolean taken = controlledListManager.getAllLists().stream()
                .anyMatch(l -> l.name().equalsIgnoreCase(name) && !l.id().equals(excludeListId));
        if (taken) {
            throw new IllegalArgumentException("A controlled list named '" + name + "' already exists");
        }
    }

    private static List<ReplaceControlledListMutation.Entry> toReplaceEntries(List<UpdateControlledListMutation.Entry> entries) {
        return entries.stream().map(e -> new ReplaceControlledListMutation.Entry(e.value(), e.label())).toList();
    }
}

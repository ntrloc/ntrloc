package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DeleteControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetPropertyControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.UpdateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Applies the list-centric controlled-list mutations -- controlled lists are a first-class,
// reusable schema element now, not a per-property appendage. (The property-keyed
// ReplaceControlledListMutation still exists for API back-compat and is handled inline in
// SchemaManager.)
@Component
class ControlledListMutationApplier {

    private final ControlledListManager controlledListManager;
    private final SchemaRepository repo;

    ControlledListMutationApplier(ControlledListManager controlledListManager, SchemaRepository repo) {
        this.controlledListManager = controlledListManager;
        this.repo = repo;
    }

    boolean apply(DefinitionMutation mutation) {
        if (mutation instanceof CreateControlledListMutation m) {
            requireNameAvailable(m.name(), null);
            var list = controlledListManager.createList(m.name(), m.valueType()); // validates SUPPORTED_TYPES
            controlledListManager.addValues(list.id(), toValueEntries(m.values()));
        } else if (mutation instanceof UpdateControlledListMutation m) {
            var list = controlledListManager.getListById(m.listId())
                    .orElseThrow(() -> new IllegalArgumentException("No controlled list: " + m.listId()));
            if (m.name() != null) {
                requireNameAvailable(m.name(), list.id());
                controlledListManager.renameList(list.id(), m.name());
            }
            if (m.values() != null) {
                controlledListManager.replaceValues(list.id(), list.valueType(), toReplaceEntries(m.values()));
            }
        } else if (mutation instanceof DeleteControlledListMutation m) {
            controlledListManager.deleteList(m.listId());
        } else if (mutation instanceof SetPropertyControlledListMutation m) {
            applySetProperty(m);
        } else {
            return false;
        }
        return true;
    }

    private void applySetProperty(SetPropertyControlledListMutation m) {
        if (m.listId() == null) {
            controlledListManager.clearPropertyControlledList(m.propertyId());
            return;
        }
        AdminPropertyDefinitionView property = repo.findProperty(m.propertyId())
                .orElseThrow(() -> new IllegalArgumentException("No property: " + m.propertyId()));
        if (!ControlledListManager.SUPPORTED_TYPES.contains(property.type())) {
            throw new IllegalArgumentException(
                    "Controlled lists are not supported for property type: " + property.type());
        }
        var list = controlledListManager.getListById(m.listId())
                .orElseThrow(() -> new IllegalArgumentException("No controlled list: " + m.listId()));
        if (list.valueType() != property.type()) {
            throw new IllegalArgumentException("Controlled list '" + list.name() + "' is " + list.valueType()
                    + " but property '" + property.name() + "' is " + property.type());
        }
        controlledListManager.setPropertyControlledList(m.propertyId(), m.listId());
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

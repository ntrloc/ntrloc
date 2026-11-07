package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.mutation.ReplaceControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.repository.ControlledListStore;
import org.ntrloc.graph.db.partition.schema.repository.ControlledListStore.ControlledList;
import org.ntrloc.graph.db.partition.schema.repository.ControlledListStore.ValueEntry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Controlled lists' rules -- supported value types, the invariants a list's values hold, typing
// values as they arrive -- over ControlledListStore.
@Service
public class ControlledListService {

    private final ControlledListStore store;

    public ControlledListService(ControlledListStore store) {
        this.store = store;
    }

    public ControlledList createList(String name, PropertyType valueType) {
        if (!ControlledListValues.SUPPORTED_TYPES.contains(valueType)) {
            throw new IllegalArgumentException("Controlled lists are not supported for property type: " + valueType);
        }
        return store.createList(name, valueType);
    }

    // Every real write replaces a list's whole value set (replaceValues, and creating a list seeds
    // through it), so validating the submitted entries here covers them all.
    public void addValues(UUID listId, List<ValueEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        validateEntries(entries);
        store.addValues(listId, entries);
    }

    public void replaceValues(UUID listId, PropertyType valueType, List<ReplaceControlledListMutation.Entry> entries) {
        store.deleteValues(listId);
        List<ValueEntry> typed = new ArrayList<>(entries.size());
        for (var entry : entries) {
            typed.add(new ValueEntry(ControlledListValues.parse(valueType, entry.value()), entry.label()));
        }
        addValues(listId, typed);
    }

    // Checked against the whole submitted set, since a write always replaces all of a list's values:
    // (1) values are unique, so membership is unambiguous; (2) either every value has a label or
    // none does, so a client learns from the schema whether values carry labels; (3) labels, when
    // used, are unique, so a person can tell values apart.
    private static void validateEntries(List<ValueEntry> entries) {
        // Typed already, so equal values in different forms (2 and 2.0) are caught too.
        Set<Object> seenValues = new HashSet<>();
        for (ValueEntry entry : entries) {
            if (!seenValues.add(entry.value())) {
                throw new IllegalArgumentException("Duplicate controlled list value: " + entry.value());
            }
        }

        long labeledCount = entries.stream().filter(e -> hasLabel(e.label())).count();
        if (labeledCount > 0 && labeledCount < entries.size()) {
            throw new IllegalArgumentException(
                    "A controlled list's values must either all have a label or all have none -- mixing labeled and unlabeled values isn't allowed");
        }
        if (labeledCount > 0) {
            Set<String> seenLabels = new HashSet<>();
            for (ValueEntry entry : entries) {
                if (!seenLabels.add(entry.label())) {
                    throw new IllegalArgumentException("Duplicate controlled list label: " + entry.label());
                }
            }
        }
    }

    private static boolean hasLabel(String label) {
        return label != null && !label.isBlank();
    }

    // Attaches a list to a property, after checking the property's type can have one and matches the
    // list's value type.
    public void attachToProperty(AdminPropertyDefinitionView property, UUID listId) {
        if (!ControlledListValues.SUPPORTED_TYPES.contains(property.type())) {
            throw new IllegalArgumentException(
                    "Controlled lists are not supported for property type: " + property.type());
        }
        var list = store.getListById(listId)
                .orElseThrow(() -> new IllegalArgumentException("No controlled list: " + listId));
        if (list.valueType() != property.type()) {
            throw new IllegalArgumentException("Controlled list '" + list.name() + "' is " + list.valueType()
                    + " but property '" + property.name() + "' is " + property.type());
        }
        store.setPropertyControlledList(property.id(), listId);
    }

    // An item's value as it arrived from JSON (e.g. an Integer for an INTEGER list, or a DATETIME at
    // any offset), put in the column's own type before it's looked for.
    public boolean containsValue(UUID listId, PropertyType valueType, Object value) {
        Object normalized = ControlledListValues.fromItemValue(valueType, value);
        return normalized != null && store.containsValue(listId, normalized);
    }

    // Projection rule (4): a controlled-list-backed property projects as a bare scalar when its list
    // has no labels, or as {value,label} when it does. A list is fully labeled or not at all
    // (validateEntries), so an empty map means "don't wrap". Looked up by a value in any of its
    // forms: callers have JSONB ->>'...' text, or whatever number Jackson produced.
    public ControlledListLabels getLabels(UUID listId, PropertyType valueType) {
        List<AllowedValue> values = store.getValues(listId, valueType);
        Map<String, String> byKey = new HashMap<>();
        if (values.stream().anyMatch(v -> hasLabel(v.label()))) {
            values.forEach(v -> byKey.put(ControlledListValues.key(valueType, v.value()), v.label()));
        }
        return new ControlledListLabels(valueType, byKey);
    }

    public List<AllowedValue> getValues(UUID listId, PropertyType valueType) {
        return store.getValues(listId, valueType);
    }

    public Optional<ControlledList> getListById(UUID listId) {
        return store.getListById(listId);
    }

    public Optional<ControlledList> getListForProperty(UUID propertyId) {
        return store.getListForProperty(propertyId);
    }
}

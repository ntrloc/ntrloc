package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;

import java.util.Map;

// A controlled list's labels, looked up by a value in any of its forms (see ControlledListValues.key).
// Empty when the list has no labels -- validateEntries keeps a list's labels all-or-none.
public record ControlledListLabels(PropertyType valueType, Map<String, String> byKey) {

    public boolean isEmpty() {
        return byKey.isEmpty();
    }

    // The label for a value, or null when it has none.
    public String labelFor(Object value) {
        return byKey.get(ControlledListValues.key(valueType, value));
    }
}

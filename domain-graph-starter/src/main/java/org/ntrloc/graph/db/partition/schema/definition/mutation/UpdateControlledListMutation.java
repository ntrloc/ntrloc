package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

// name and values are independently optional: a null name means "don't rename", a null values
// means "don't touch the values". The editor lazy-loads a list's values, so a rename issued
// before the values were ever loaded must not send an empty list and wipe them.
public record UpdateControlledListMutation(UUID listId, @Nullable String name, @Nullable List<Entry> values)
        implements DefinitionMutation {
    public record Entry(String value, String label) {}
}

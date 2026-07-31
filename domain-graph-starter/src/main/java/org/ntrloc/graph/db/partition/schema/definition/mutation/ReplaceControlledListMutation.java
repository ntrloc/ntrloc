package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.List;
import java.util.UUID;

public record ReplaceControlledListMutation(UUID propertyId, List<Entry> values) implements DefinitionMutation {
    public record Entry(String value, String label) {}
}

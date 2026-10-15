package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.springframework.lang.Nullable;

import java.util.List;

// Creates a first-class, reusable controlled list. Not tied to a property -- properties point at
// it afterward via SetPropertyControlledListMutation. value/label are always strings on the wire
// (coerced to the list's valueType by ControlledListManager). refId is optional -- a
// caller-assigned tag letting a SetPropertyControlledListMutation later in the same request
// reference this not-yet-persisted list before it has a real id (mirrors ItemCreateMutation's own
// refId for items/links).
public record CreateControlledListMutation(@Nullable String refId, String name, PropertyType valueType, List<Entry> values)
        implements DefinitionMutation {
    public record Entry(String value, String label) {}
}

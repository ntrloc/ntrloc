package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;

import java.util.List;

// Creates a first-class, reusable controlled list. Not tied to a property -- properties point at
// it afterward via SetPropertyControlledListMutation. value/label are always strings on the wire
// (coerced to the list's valueType by ControlledListManager).
public record CreateControlledListMutation(String name, PropertyType valueType, List<Entry> values)
        implements DefinitionMutation {
    public record Entry(String value, String label) {}
}

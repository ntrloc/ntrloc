package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;

import java.util.List;

// properties is recursive -- meaningful only when propertyType is OBJECT, letting an item/trait/
// link's initial property list (or a single newly-created property) bring its own nested
// structure along atomically, in the same create call, rather than needing a second round-trip
// once the parent has a real id. Normalized to an empty list rather than null so every consumer
// (PropertyMutationApplier.createPropertyRecursive) can iterate it unconditionally.
public record CreatePropertyDefinitionMutation(String name, String description, PropertyType propertyType, PropertyCardinality cardinality, PropertyUsage usage, List<CreatePropertyDefinitionMutation> properties) {
    public CreatePropertyDefinitionMutation {
        if (properties == null) properties = List.of();
    }
}

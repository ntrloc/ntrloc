package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;

import java.util.List;
import java.util.UUID;

// Trait-owned mirror of CreateItemPropertyDefinitionMutation (see its own comment) -- lets a
// brand-new property (OBJECT or otherwise) be added to an already-existing trait, together with
// its full nested structure if any, atomically, in one mutation. Every implementer picks it up
// through the existing trait-inheritance merge (SchemaViewBuilder), same as a property that was
// already on the trait when an item first implemented it.
public record CreateTraitPropertyDefinitionMutation(UUID traitId, String name, String description, PropertyType propertyType, PropertyCardinality cardinality, PropertyUsage usage, boolean facetable, List<CreatePropertyDefinitionMutation> properties) implements DefinitionMutation {
    public CreateTraitPropertyDefinitionMutation {
        if (properties == null) properties = List.of();
    }
}

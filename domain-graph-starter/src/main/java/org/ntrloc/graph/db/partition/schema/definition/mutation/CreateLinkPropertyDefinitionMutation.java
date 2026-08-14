package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;

import java.util.List;
import java.util.UUID;

// properties is recursive -- see CreatePropertyDefinitionMutation's own comment.
public record CreateLinkPropertyDefinitionMutation(UUID linkId, String name, String description, PropertyType propertyType, PropertyCardinality cardinality, PropertyUsage usage, boolean facetable, List<CreatePropertyDefinitionMutation> properties) implements DefinitionMutation {
    public CreateLinkPropertyDefinitionMutation {
        if (properties == null) properties = List.of();
    }
}

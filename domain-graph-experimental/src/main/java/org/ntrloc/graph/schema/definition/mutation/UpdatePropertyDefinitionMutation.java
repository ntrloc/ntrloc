package org.ntrloc.graph.schema.definition.mutation;

import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyType;
import org.ntrloc.graph.schema.definition.PropertyUsage;

import java.util.UUID;

public record UpdatePropertyDefinitionMutation(UUID id, String name, String description, PropertyType propertyType, PropertyCardinality cardinality, PropertyUsage usage) implements DefinitionMutation {
}

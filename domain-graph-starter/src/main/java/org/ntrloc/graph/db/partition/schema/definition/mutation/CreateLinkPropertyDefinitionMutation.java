package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;

import java.util.UUID;

public record CreateLinkPropertyDefinitionMutation(UUID linkId, String name, String description, PropertyType propertyType, PropertyCardinality cardinality, PropertyUsage usage) implements DefinitionMutation {
}

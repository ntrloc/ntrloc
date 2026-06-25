package org.ntrloc.graph.schema.definition.operation;

import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyType;
import org.ntrloc.graph.schema.definition.PropertyUsage;

import java.util.UUID;

public record CreateItemPropertyOperation(UUID itemId, String name, String description, PropertyType propertyType, PropertyCardinality cardinality, PropertyUsage usage) implements SchemaOperation {
}

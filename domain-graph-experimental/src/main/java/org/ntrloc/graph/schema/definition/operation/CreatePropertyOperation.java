package org.ntrloc.graph.schema.definition.operation;

import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyType;
import org.ntrloc.graph.schema.definition.PropertyUsage;

public record CreatePropertyOperation(String name, String description, PropertyType propertyType, PropertyCardinality cardinality, PropertyUsage usage) {
}

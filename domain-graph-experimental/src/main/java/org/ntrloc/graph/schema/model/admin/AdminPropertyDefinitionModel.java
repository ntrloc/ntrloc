package org.ntrloc.graph.schema.model.admin;

import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyUsage;
import org.ntrloc.graph.schema.definition.PropertyType;

import java.util.UUID;

public record AdminPropertyDefinitionModel(UUID id, String name, String description, PropertyType type, PropertyCardinality cardinality, PropertyUsage usage) {
}

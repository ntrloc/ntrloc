package org.ntrloc.graph.schema.model.calculated;

import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyType;

import java.util.UUID;

public record PropertyDefinitionModel(UUID id, String name, String description, PropertyType type, PropertyCardinality cardinality) {
}

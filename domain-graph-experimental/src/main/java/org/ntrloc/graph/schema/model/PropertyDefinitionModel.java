package org.ntrloc.graph.schema.model;

import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyType;

import java.util.UUID;

public record PropertyDefinitionModel(UUID id, String name, PropertyType type, PropertyCardinality cardinality) {
}

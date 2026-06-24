package org.ntrloc.graph.schema.model;

import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyType;

import java.util.Set;

public record PropertyTypeModel(PropertyType type, Set<PropertyCardinality> validCardinalities) {
}

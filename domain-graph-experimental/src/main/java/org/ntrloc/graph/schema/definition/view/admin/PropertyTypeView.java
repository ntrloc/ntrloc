package org.ntrloc.graph.schema.definition.view.admin;

import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyType;

import java.util.Set;

public record PropertyTypeView(PropertyType type, Set<PropertyCardinality> validCardinalities) {
}

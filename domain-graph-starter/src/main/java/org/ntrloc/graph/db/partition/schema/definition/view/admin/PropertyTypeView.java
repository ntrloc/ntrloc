package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;

import java.util.Set;

public record PropertyTypeView(PropertyType type, Set<PropertyCardinality> validCardinalities) {
}

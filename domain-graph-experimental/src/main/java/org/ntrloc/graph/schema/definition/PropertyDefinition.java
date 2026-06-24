package org.ntrloc.graph.schema.definition;

public record PropertyDefinition(String name, String description, PropertyType type, PropertyCardinality cardinality, PropertyRequirement requirement) {
}

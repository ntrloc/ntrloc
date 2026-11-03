package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;

import java.util.UUID;

// A property's type is fixed once created; its cardinality can change.
public record UpdatePropertyDefinitionMutation(UUID id, String name, String description, PropertyCardinality cardinality, PropertyUsage usage, boolean facetable) implements DefinitionMutation {
}

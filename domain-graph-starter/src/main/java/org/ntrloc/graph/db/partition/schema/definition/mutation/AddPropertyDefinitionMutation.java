package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;

import java.util.UUID;

// Adds a new property under an existing parent -- an item type, a trait, a link type, or a
// property group (parentKind says which, parentId is that parent's id). Same (kind, id) addressing
// MovePropertyDefinitionMutation already uses for its target.
public record AddPropertyDefinitionMutation(PropertyContainerKind parentKind, UUID parentId, String name, String description, PropertyType propertyType, PropertyCardinality cardinality, PropertyUsage usage, boolean facetable) implements DefinitionMutation {
}

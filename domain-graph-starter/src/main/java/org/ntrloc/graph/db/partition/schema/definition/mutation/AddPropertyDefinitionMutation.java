package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.springframework.lang.Nullable;

import java.util.UUID;

// Adds a new property under an existing parent -- an item type, a trait, a link type, or a
// property group (parentKind says which, parentId is that parent's id). Same (kind, id) addressing
// MovePropertyDefinitionMutation already uses for its target. controlledListId, when given, attaches
// that existing list to the new property in the same step -- the property's id isn't known until
// it's created, so a separate SetPropertyControlledListMutation couldn't name it.
public record AddPropertyDefinitionMutation(PropertyContainerKind parentKind, UUID parentId, String name, String description,
                                            PropertyType propertyType, PropertyCardinality cardinality, PropertyUsage usage,
                                            boolean facetable, @Nullable UUID controlledListId) implements DefinitionMutation {

    public AddPropertyDefinitionMutation(PropertyContainerKind parentKind, UUID parentId, String name, String description,
                                         PropertyType propertyType, PropertyCardinality cardinality, PropertyUsage usage,
                                         boolean facetable) {
        this(parentKind, parentId, name, description, propertyType, cardinality, usage, facetable, null);
    }
}

package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;

import java.util.List;
import java.util.UUID;

// Creates a new property nested inside an existing OBJECT-typed property (parentPropertyId).
// Mirrors CreateItemPropertyDefinitionMutation/CreateLinkPropertyDefinitionMutation exactly, one
// level down: the parent is a property instead of an item/link type. properties is recursive --
// see CreatePropertyDefinitionMutation's own comment -- so a new grandchild subtree can be
// created together with this new child in one call.
public record CreatePropertyPropertyDefinitionMutation(UUID parentPropertyId, String name, String description, PropertyType propertyType, PropertyCardinality cardinality, PropertyUsage usage, boolean facetable, List<CreatePropertyDefinitionMutation> properties) implements DefinitionMutation {
    public CreatePropertyPropertyDefinitionMutation {
        if (properties == null) properties = List.of();
    }
}

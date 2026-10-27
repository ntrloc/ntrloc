package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.springframework.lang.Nullable;

import java.util.UUID;

// The payload for one new property (always a leaf) embedded in a larger create -- an item/trait/
// link type's initial contents, or a new property group's. Not itself a DefinitionMutation; the
// standalone form is AddPropertyDefinitionMutation. controlledListId, when given, attaches that
// existing list as the property is created.
public record CreatePropertyDefinitionMutation(String name, String description, PropertyType propertyType, PropertyCardinality cardinality,
                                               PropertyUsage usage, boolean facetable, @Nullable UUID controlledListId) {

    public CreatePropertyDefinitionMutation(String name, String description, PropertyType propertyType, PropertyCardinality cardinality,
                                            PropertyUsage usage, boolean facetable) {
        this(name, description, propertyType, cardinality, usage, facetable, null);
    }
}

package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;

import java.util.List;
import java.util.UUID;

// Adds a new property group under an existing parent (an item type, a trait, a link type, or
// another group), optionally together with its full initial contents -- see
// CreatePropertyGroupDefinitionMutation for the recursive shape. Normalized to empty lists rather
// than null.
public record AddPropertyGroupDefinitionMutation(PropertyContainerKind parentKind, UUID parentId, String name, String description,
                                                  List<CreatePropertyDefinitionMutation> properties,
                                                  List<CreatePropertyGroupDefinitionMutation> groups) implements DefinitionMutation {
    public AddPropertyGroupDefinitionMutation {
        if (properties == null) properties = List.of();
        if (groups == null) groups = List.of();
    }
}

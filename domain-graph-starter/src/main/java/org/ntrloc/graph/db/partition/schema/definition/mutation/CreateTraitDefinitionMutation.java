package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.List;

public record CreateTraitDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties,
                                             List<CreatePropertyGroupDefinitionMutation> groups) implements DefinitionMutation {

    public CreateTraitDefinitionMutation {
        if (properties == null) properties = List.of();
        if (groups == null) groups = List.of();
    }

    public CreateTraitDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties) {
        this(name, description, properties, List.of());
    }
}

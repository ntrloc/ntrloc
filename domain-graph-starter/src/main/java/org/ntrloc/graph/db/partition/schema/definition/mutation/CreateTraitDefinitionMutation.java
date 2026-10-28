package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.List;

// refId is optional, as on CreateItemDefinitionMutation: it lets a link later in the same batch
// refer to this not-yet-created trait.
public record CreateTraitDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties,
                                             List<CreatePropertyGroupDefinitionMutation> groups,
                                             @Nullable String refId) implements DefinitionMutation {

    public CreateTraitDefinitionMutation {
        if (properties == null) properties = List.of();
        if (groups == null) groups = List.of();
    }

    public CreateTraitDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties,
                                          List<CreatePropertyGroupDefinitionMutation> groups) {
        this(name, description, properties, groups, null);
    }

    public CreateTraitDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties) {
        this(name, description, properties, List.of());
    }
}

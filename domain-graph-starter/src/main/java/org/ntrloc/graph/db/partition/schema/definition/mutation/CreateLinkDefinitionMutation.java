package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.List;

public record CreateLinkDefinitionMutation(List<CreatePropertyDefinitionMutation> properties,
                                            List<CreatePerspectiveDefinitionMutation> perspectives,
                                            List<CreatePropertyGroupDefinitionMutation> groups) implements DefinitionMutation {

    public CreateLinkDefinitionMutation {
        if (properties == null) properties = List.of();
        if (groups == null) groups = List.of();
    }

    public CreateLinkDefinitionMutation(List<CreatePropertyDefinitionMutation> properties, List<CreatePerspectiveDefinitionMutation> perspectives) {
        this(properties, perspectives, List.of());
    }
}

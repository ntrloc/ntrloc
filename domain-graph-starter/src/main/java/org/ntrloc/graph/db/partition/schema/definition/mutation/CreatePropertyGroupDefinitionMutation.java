package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.List;

// The payload for one new property group together with its full contents, embedded in a larger
// create -- recursive, so a group's whole subtree can be created atomically in one mutation, the
// parent always created first so every child is attached under its parent's real id (never a
// placeholder). Normalized to empty lists rather than null so every consumer can iterate them
// unconditionally. The standalone form is AddPropertyGroupDefinitionMutation.
public record CreatePropertyGroupDefinitionMutation(String name, String description,
                                                     List<CreatePropertyDefinitionMutation> properties,
                                                     List<CreatePropertyGroupDefinitionMutation> groups) {
    public CreatePropertyGroupDefinitionMutation {
        if (properties == null) properties = List.of();
        if (groups == null) groups = List.of();
    }
}

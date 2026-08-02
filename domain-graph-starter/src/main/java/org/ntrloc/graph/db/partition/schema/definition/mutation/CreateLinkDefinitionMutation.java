package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.List;

public record CreateLinkDefinitionMutation(List<CreatePropertyDefinitionMutation> properties, List<CreatePerspectiveDefinitionMutation> perspectives) implements DefinitionMutation {
}

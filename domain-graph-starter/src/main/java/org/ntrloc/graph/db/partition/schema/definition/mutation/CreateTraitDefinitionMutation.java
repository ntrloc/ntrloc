package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.List;

public record CreateTraitDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties) implements DefinitionMutation {
}

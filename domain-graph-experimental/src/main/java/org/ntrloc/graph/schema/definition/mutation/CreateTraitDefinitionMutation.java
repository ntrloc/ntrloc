package org.ntrloc.graph.schema.definition.mutation;

import java.util.List;

public record CreateTraitDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties) implements DefinitionMutation {
}

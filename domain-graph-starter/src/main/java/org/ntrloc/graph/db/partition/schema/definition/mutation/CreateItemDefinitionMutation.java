package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

public record CreateItemDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties,
                                            @Nullable UUID supertypeId, boolean abstractType,
                                            @Nullable String displayLabelPattern) implements DefinitionMutation {
}

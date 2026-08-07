package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

public record UpdateItemDefinitionMutation(UUID id, String name, String description,
                                            @Nullable UUID supertypeId, boolean abstractType,
                                            @Nullable String displayLabelPattern) implements DefinitionMutation {
}

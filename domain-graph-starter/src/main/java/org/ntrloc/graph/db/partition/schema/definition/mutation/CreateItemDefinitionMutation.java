package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

public record CreateItemDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties,
                                            @Nullable UUID supertypeId, boolean abstractType,
                                            @Nullable String displayLabelPattern, List<UUID> traitIds,
                                            List<CreatePropertyGroupDefinitionMutation> groups) implements DefinitionMutation {

    // Jackson deserializes records via this canonical constructor directly, never the Java-only
    // overloads below -- a raw JSON mutation list that omits "traitIds" or "groups" entirely lands
    // here with them null, not List.of(), which ItemMutationApplier.applyCreate's for-each would
    // NPE on.
    public CreateItemDefinitionMutation {
        if (properties == null) properties = List.of();
        if (traitIds == null) traitIds = List.of();
        if (groups == null) groups = List.of();
    }

    // Traits and groups are optional on most callers -- these overloads keep them compiling against
    // the canonical constructor above rather than threading List.of() through every call site.
    public CreateItemDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties,
                                         @Nullable UUID supertypeId, boolean abstractType,
                                         @Nullable String displayLabelPattern, List<UUID> traitIds) {
        this(name, description, properties, supertypeId, abstractType, displayLabelPattern, traitIds, List.of());
    }

    public CreateItemDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties,
                                         @Nullable UUID supertypeId, boolean abstractType,
                                         @Nullable String displayLabelPattern) {
        this(name, description, properties, supertypeId, abstractType, displayLabelPattern, List.of(), List.of());
    }
}

package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

public record CreateItemDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties,
                                            @Nullable UUID supertypeId, boolean abstractType,
                                            @Nullable String displayLabelPattern, List<UUID> traitIds) implements DefinitionMutation {

    // Jackson deserializes records via this canonical constructor directly, never the Java-only
    // 6-arg overload below -- a raw JSON mutation list (SchemaAdminControllerIntegrationTest's own
    // "applies a raw JSON mutation list" coverage) that omits "traitIds" entirely lands here with
    // traitIds == null, not List.of(), which ItemMutationApplier.applyCreate's for-each would NPE on.
    public CreateItemDefinitionMutation {
        if (traitIds == null) traitIds = List.of();
    }

    // Traits are optional on most callers (every pre-existing construction site predates trait
    // assignment at creation time) -- this keeps them all compiling against the canonical
    // constructor above rather than threading List.of() through every call site.
    public CreateItemDefinitionMutation(String name, String description, List<CreatePropertyDefinitionMutation> properties,
                                         @Nullable UUID supertypeId, boolean abstractType,
                                         @Nullable String displayLabelPattern) {
        this(name, description, properties, supertypeId, abstractType, displayLabelPattern, List.of());
    }
}

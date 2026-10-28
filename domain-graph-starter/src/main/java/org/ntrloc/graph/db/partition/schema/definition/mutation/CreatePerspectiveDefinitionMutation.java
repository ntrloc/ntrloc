package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

// One direction of a new link, on the item type or trait it belongs to: an existing one (itemId),
// or one created earlier in the same batch (itemRefId, its CreateItemDefinitionMutation's refId).
// Exactly one of the two is given.
public record CreatePerspectiveDefinitionMutation(@Nullable UUID itemId, String name, String description, Integer minCardinality,
                                                  Integer maxCardinality, @Nullable String itemRefId) {

    public CreatePerspectiveDefinitionMutation(UUID itemId, String name, String description, Integer minCardinality, Integer maxCardinality) {
        this(itemId, name, description, minCardinality, maxCardinality, null);
    }
}

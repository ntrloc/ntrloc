package org.ntrloc.graph.db.partition.schema.definition.mutation;

import tools.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

import java.util.UUID;

// Its states are named by id (fromStateId / toStateId) or, for one created earlier in the same
// batch, by its refId instead (fromState / toState). refId is optional: a caller's tag for the new
// transition, so a SetStateMachineLayoutMutation later in the batch can draw it before it has an id.
public record CreateTransitionMutation(@Nullable UUID fromStateId, @Nullable UUID toStateId, String name, @Nullable String description,
                                       @Nullable String processId, @Nullable JsonNode guardCondition,
                                       @Nullable String fromState, @Nullable String toState, @Nullable String refId)
        implements DefinitionMutation {

    public CreateTransitionMutation(UUID fromStateId, UUID toStateId, String name, @Nullable String description,
                                    @Nullable String processId, @Nullable JsonNode guardCondition) {
        this(fromStateId, toStateId, name, description, processId, guardCondition, null, null, null);
    }
}

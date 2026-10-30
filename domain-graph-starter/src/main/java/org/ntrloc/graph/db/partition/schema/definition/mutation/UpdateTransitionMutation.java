package org.ntrloc.graph.db.partition.schema.definition.mutation;

import tools.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

import java.util.UUID;

// fromState / toState, when given, move the transition to lead from or to another state of the same
// machine -- the same transition, so what refers to it (marker grants) stays with it. Each is a
// state's id, or the refId of a state created earlier in the same batch.
public record UpdateTransitionMutation(UUID id, String name, @Nullable String description, @Nullable String processId,
                                       @Nullable JsonNode guardCondition, @Nullable String fromState, @Nullable String toState)
        implements DefinitionMutation {

    public UpdateTransitionMutation(UUID id, String name, @Nullable String description, @Nullable String processId,
                                    @Nullable JsonNode guardCondition) {
        this(id, name, description, processId, guardCondition, null, null);
    }
}

package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

// Replaces how a state machine's diagram is drawn -- the admin UI's editor owns its shape (state
// positions, transition routes) and keeps it in step with the machine; the server only stores it.
// Null clears it. A key anywhere in it that's the refId of a state created earlier in the same batch
// is stored as that state's id, and the same for a transition.
public record SetStateMachineLayoutMutation(UUID stateMachineId, @Nullable JsonNode layout) implements DefinitionMutation {
}

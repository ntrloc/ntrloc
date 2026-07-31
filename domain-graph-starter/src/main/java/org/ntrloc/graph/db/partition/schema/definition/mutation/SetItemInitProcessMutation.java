package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

// Sets (or clears) the state machine initialization process for an item type. Only meaningful
// when the item type has more than one initial state; single-initial-state items enter that
// state automatically without running a process.
public record SetItemInitProcessMutation(UUID itemId, @Nullable String initProcessId) implements DefinitionMutation {
}

package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

// Only ever creates NORMAL states -- the START/END pseudostates are created with the machine
// (CreateStateMachineMutation) and are not mutable through this.
public record CreateStateMutation(UUID stateMachineId, String name, @Nullable String description,
                                  @Nullable String entryProcessId, @Nullable String exitProcessId,
                                  @Nullable String entryMarkerDecisionKey) implements DefinitionMutation {
}

package org.ntrloc.graph.mutation;

import org.springframework.lang.Nullable;

import java.util.UUID;

// A request to start a state machine on an item (transitionId null: the START state's transition) or to
// execute one of its transitions. Not part of the JSON MutationRequest: state changes only through a
// transition, never as a raw value a client sets.
public record StateTransitionRequest(UUID itemId, String stateMachineName, @Nullable UUID transitionId) {

    public static StateTransitionRequest start(UUID itemId, String stateMachineName) {
        return new StateTransitionRequest(itemId, stateMachineName, null);
    }

    public static StateTransitionRequest transition(UUID itemId, String stateMachineName, UUID transitionId) {
        return new StateTransitionRequest(itemId, stateMachineName, transitionId);
    }

    public boolean isStart() {
        return transitionId == null;
    }
}

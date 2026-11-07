package org.ntrloc.graph.db;

import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.mutation.AbortResponse;
import org.ntrloc.graph.mutation.CommitResponse;
import org.ntrloc.graph.mutation.MutationRequest;
import org.ntrloc.graph.mutation.MutationRequestProcessor;
import org.ntrloc.graph.mutation.MutationResponse;
import org.ntrloc.graph.mutation.StateTransitionRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Entity Write (docs/ntrloc-architecture-rationalization.md §5): mutations, their two-phase confirmation,
// and state machine starts and transitions -- all through MutationRequestProcessor, the one write path.
// MutationRequestProcessor does its own permission checks; @PreAuthorize guarantees a principal.
@Service
public class EntityWriteService {

    private final MutationRequestProcessor mutationRequestProcessor;

    public EntityWriteService(MutationRequestProcessor mutationRequestProcessor) {
        this.mutationRequestProcessor = mutationRequestProcessor;
    }

    @PreAuthorize("#principal != null")
    public MutationResponse mutate(MutationRequest request, NtrlocPrincipal principal) {
        return mutationRequestProcessor.process(request, principal);
    }

    public CommitResponse confirmCommit(UUID transactionId) {
        return mutationRequestProcessor.confirmCommit(transactionId);
    }

    public AbortResponse confirmAbort(UUID transactionId) {
        return mutationRequestProcessor.confirmAbort(transactionId);
    }

    @PreAuthorize("#principal != null")
    public void startStateMachine(UUID itemId, String stateMachineName, NtrlocPrincipal principal) {
        mutationRequestProcessor.processTransition(StateTransitionRequest.start(itemId, stateMachineName), principal);
    }

    @PreAuthorize("#principal != null")
    public void executeTransition(UUID itemId, String stateMachineName, UUID transitionId, NtrlocPrincipal principal) {
        mutationRequestProcessor.processTransition(StateTransitionRequest.transition(itemId, stateMachineName, transitionId), principal);
    }
}

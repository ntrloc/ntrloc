package org.ntrloc.graph.db;

import org.ntrloc.graph.mutation.AbortResponse;
import org.ntrloc.graph.mutation.CommitResponse;
import org.ntrloc.graph.mutation.MutationRequest;
import org.ntrloc.graph.mutation.MutationResponse;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.projection.CollectionProjectionSpec;
import org.ntrloc.graph.projection.ProjectedItem;
import org.ntrloc.graph.projection.ProjectionResult;
import org.ntrloc.graph.projection.ItemProjectionSpec;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.Optional;
import java.util.UUID;

// The single entry point for both directions of the entity system: reads (project) and writes
// (mutate). mutate() is a pure passthrough to MutationRequestProcessor today (see
// EntityManagerImpl) -- project() enforces per-item-type read permissions (PermissionService), and
// so does mutate(), via MutationRequestProcessor's own permission checks (its class comment
// documents exactly which methods enforce what; a denial there surfaces as a MutationValidationException,
// not a silent no-op). Every method here additionally demands a real, non-null principal via
// @PreAuthorize, enforced by Spring (see docs/ntrloc-principal-enforcement-plan.md, Part C) --
// not a scattered null check callers have to remember, and not optional: there
// is no legitimate anonymous call into this interface, full stop.
public interface EntityManager {

    @PreAuthorize("#principal != null")
    Optional<ProjectedItem> project(ItemProjectionSpec spec, String binaryBaseUrl, NtrlocPrincipal principal);

    @PreAuthorize("#principal != null")
    ProjectionResult project(CollectionProjectionSpec spec, String binaryBaseUrl, NtrlocPrincipal principal);

    // principal is mandatory, same as every other method here -- see the class comment. It is
    // still attribution for the ledger (who made this change) in addition to being demanded by
    // MutationRequestProcessor's own permission checks; the two are not in tension, since "there
    // is no anonymous mutation" means attribution is never actually missing either.
    @PreAuthorize("#principal != null")
    MutationResponse mutate(MutationRequest request, NtrlocPrincipal principal);

    // Confirms a transaction previously prepared with MutationRequest.commit() = false. See
    // MutationRequestProcessor.confirmCommit.
    CommitResponse confirmCommit(UUID transactionId);

    // Discards a transaction previously prepared with MutationRequest.commit() = false. See
    // MutationRequestProcessor.confirmAbort.
    AbortResponse confirmAbort(UUID transactionId);

    // Begin an item's participation in a state machine: enters the state its START pseudostate
    // points at. Enforces state-machine:start (marker-scoped) and rejects if the machine is already
    // active on the item or has no wired start transition. No process execution yet -- v1 just moves
    // the current state. Throws ResponseStatusException with the appropriate status on failure.
    @PreAuthorize("#principal != null")
    void startStateMachine(UUID itemId, String stateMachineName, NtrlocPrincipal principal);

    // Advance an active state machine along one of the current state's outgoing transitions
    // (identified by transition id). Enforces transition:execute and the transition's guard
    // (authoritatively), and detaches the item from the machine when the target is the END
    // pseudostate. No exit/action/entry process execution yet.
    @PreAuthorize("#principal != null")
    void executeTransition(UUID itemId, String stateMachineName, UUID transitionId, NtrlocPrincipal principal);
}

package org.ntrloc.graph.db;

import org.ntrloc.graph.db.mutation.MutationRequest;
import org.ntrloc.graph.db.mutation.MutationResponse;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.projection.CollectionProjectionSpec;
import org.ntrloc.graph.db.projection.ProjectedItem;
import org.ntrloc.graph.db.projection.ProjectionResult;
import org.ntrloc.graph.db.projection.SingleItemProjectionSpec;
import org.springframework.lang.Nullable;

import java.util.Optional;
import java.util.UUID;

// The single entry point for both directions of the entity system: reads (project) and writes
// (mutate). mutate() is a pure passthrough to MutationRequestProcessor today (see
// EntityManagerImpl) -- project() already enforces per-item-type read permissions
// (PermissionService), mutations don't yet (MutationRequestProcessor's own class comment: "not-yet-
// built component"). That gap is a deliberate, separate follow-up, not bundled into this pass --
// this method exists now so callers have one seam to depend on regardless.
public interface EntityManager {

    Optional<ProjectedItem> project(SingleItemProjectionSpec spec, String binaryBaseUrl, NtrlocPrincipal principal);

    ProjectionResult project(CollectionProjectionSpec spec, String binaryBaseUrl, NtrlocPrincipal principal);

    // principal is @Nullable, unlike project()'s -- it's attribution for the ledger (who made
    // this change), not a permission gate (mutations don't enforce one yet, see class comment
    // above). An unresolvable/absent principal is a real, displayable state, not a reason to
    // refuse the mutation.
    MutationResponse mutate(MutationRequest request, @Nullable NtrlocPrincipal principal);

    // Ledger-backed like mutate() above (an ItemUpdateEntry with only its stateChanges facet
    // populated, staged/committed through the same coordinator) -- but deliberately still minimal:
    // it does not check that stateName is actually reachable from the item's current state via a
    // defined transition, and (like mutate()) doesn't enforce permissions. Transition validity
    // enforcement is a separate, not-yet-built follow-up; this method exists now so state changes
    // are audited and available as marker-rule trigger input once that system exists, without
    // waiting on transition validation to be designed first.
    void setItemState(UUID itemId, String stateMachineName, String stateName);

    // Begin an item's participation in a state machine: enters the state its START pseudostate
    // points at. Enforces state-machine:start (marker-scoped) and rejects if the machine is already
    // active on the item or has no wired start transition. No process execution yet -- v1 just moves
    // the current state. Throws ResponseStatusException with the appropriate status on failure.
    void startStateMachine(UUID itemId, String stateMachineName, NtrlocPrincipal principal);

    // Advance an active state machine along one of the current state's outgoing transitions
    // (identified by transition id). Enforces transition:execute and the transition's guard
    // (authoritatively), and detaches the item from the machine when the target is the END
    // pseudostate. No exit/action/entry process execution yet.
    void executeTransition(UUID itemId, String stateMachineName, UUID transitionId, NtrlocPrincipal principal);
}

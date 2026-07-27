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

    // Minimal, direct register write, deliberately outside the ledger-backed mutate() above -- see
    // RegisterPartitionManager.setItemState's own comment for why. Exists only so current-state
    // querying/faceting has real data to test against before real transition execution is built.
    void setItemState(UUID itemId, String stateMachineName, String stateName);
}

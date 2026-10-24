package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.ManuallyAppliedMarker;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Minimal, direct marker assignment -- ledgered and posted to the register like any other
// mutation, but deliberately decoupled from the mutation-validation pipeline and from *how*
// markers get assigned automatically (see the marker decision pipeline / PipelineAppliedMarker for
// that). Every marker change made through this service is manual by construction --
// ManuallyAppliedMarker requires both who did it and why, so both are required parameters here too.
//
// Items only -- markers never apply to links (see docs/ntrloc-marker-admin-ui-design-notes.md,
// "Decision: markers apply to items only").
//
// Takes a real NtrlocPrincipal, not a bare user id -- same reasoning as every other top-level
// principal-accepting method (docs/ntrloc-principal-enforcement-plan.md, Part C): the coordinator
// itself never reads SecurityContextHolder, so whoever calls this must already hold a resolved
// principal. principal.id() feeds both the ledger entry's own created_by (Part E) and
// ManuallyAppliedMarker's own payload-level userId.
@Service
public class ManualMarkerAssignmentService {

    private final LedgerRegisterCoordinator coordinator;

    public ManualMarkerAssignmentService(LedgerRegisterCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PreAuthorize("#principal != null")
    public void addItemMarker(UUID itemId, UUID markerId, NtrlocPrincipal principal, String reason) {
        commit(List.of(new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(), Set.of(manual(markerId, principal.id(), reason)), Set.of())), principal);
    }

    @PreAuthorize("#principal != null")
    public void removeItemMarker(UUID itemId, UUID markerId, NtrlocPrincipal principal, String reason) {
        commit(List.of(new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(), Set.of(), Set.of(manual(markerId, principal.id(), reason)))), principal);
    }

    private ManuallyAppliedMarker manual(UUID markerId, UUID actorUserId, String reason) {
        return new ManuallyAppliedMarker(markerId, actorUserId, reason);
    }

    private void commit(List<LedgerEntry> entries, NtrlocPrincipal principal) {
        UUID transactionId = UUID.randomUUID();
        UUID commitId = UUID.randomUUID();
        coordinator.prepare(entries, transactionId, principal.id(), OffsetDateTime.now(ZoneOffset.UTC));
        coordinator.commit(transactionId, commitId);
    }
}

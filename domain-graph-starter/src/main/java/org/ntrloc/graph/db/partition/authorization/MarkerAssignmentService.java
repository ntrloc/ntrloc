package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.LinkUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.ManuallyAppliedMarker;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Minimal, direct marker assignment -- ledgered and posted to the register like any other
// mutation, but deliberately decoupled from the mutation-validation pipeline and from *how*
// markers get assigned via rules (a rule engine is still unbuilt; see RuleAppliedMarker for that
// shape once it exists). Every marker change made through this service is manual by construction --
// ManuallyAppliedMarker requires both who did it and why, so both are required parameters here too.
@Service
public class MarkerAssignmentService {

    private final LedgerRegisterCoordinator coordinator;

    public MarkerAssignmentService(LedgerRegisterCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void addItemMarker(UUID itemId, UUID markerId, String actorExternalId, String reason) {
        commit(List.of(new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(manual(markerId, actorExternalId, reason)), Set.of())), actorExternalId);
    }

    public void removeItemMarker(UUID itemId, UUID markerId, String actorExternalId, String reason) {
        commit(List.of(new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(), Set.of(manual(markerId, actorExternalId, reason)))), actorExternalId);
    }

    public void addLinkMarker(UUID linkId, UUID markerId, String actorExternalId, String reason) {
        commit(List.of(new LinkUpdateEntry(linkId, Map.of(), Set.of(manual(markerId, actorExternalId, reason)), Set.of())), actorExternalId);
    }

    public void removeLinkMarker(UUID linkId, UUID markerId, String actorExternalId, String reason) {
        commit(List.of(new LinkUpdateEntry(linkId, Map.of(), Set.of(), Set.of(manual(markerId, actorExternalId, reason)))), actorExternalId);
    }

    private ManuallyAppliedMarker manual(UUID markerId, String actorExternalId, String reason) {
        return new ManuallyAppliedMarker(markerId, actorExternalId, reason);
    }

    private void commit(List<LedgerEntry> entries, String actorExternalId) {
        UUID transactionId = UUID.randomUUID();
        UUID commitId = UUID.randomUUID();
        coordinator.prepare(entries, transactionId, actorExternalId);
        coordinator.commit(transactionId, commitId);
    }
}

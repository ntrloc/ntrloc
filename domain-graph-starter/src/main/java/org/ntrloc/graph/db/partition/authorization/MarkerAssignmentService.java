package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.ledger.ItemMarkerAddEntry;
import org.ntrloc.graph.db.partition.ledger.ItemMarkerRemoveEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.LinkMarkerAddEntry;
import org.ntrloc.graph.db.partition.ledger.LinkMarkerRemoveEntry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

// Minimal, direct marker assignment -- ledgered and posted to the register like any other
// mutation, but deliberately decoupled from the mutation-validation pipeline and from *how*
// markers get assigned (ad-hoc TTL, rule engine -- both still unbuilt). This only answers "how
// does a marker get onto an item/link, correctly audited," not "who's allowed to do that or why."
@Service
public class MarkerAssignmentService {

    private final LedgerRegisterCoordinator coordinator;

    public MarkerAssignmentService(LedgerRegisterCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public void addItemMarker(UUID itemId, UUID markerId, String actorExternalId) {
        commit(List.of(new ItemMarkerAddEntry(itemId, markerId)), actorExternalId);
    }

    public void removeItemMarker(UUID itemId, UUID markerId, String actorExternalId) {
        commit(List.of(new ItemMarkerRemoveEntry(itemId, markerId)), actorExternalId);
    }

    public void addLinkMarker(UUID linkId, UUID markerId, String actorExternalId) {
        commit(List.of(new LinkMarkerAddEntry(linkId, markerId)), actorExternalId);
    }

    public void removeLinkMarker(UUID linkId, UUID markerId, String actorExternalId) {
        commit(List.of(new LinkMarkerRemoveEntry(linkId, markerId)), actorExternalId);
    }

    private void commit(List<LedgerEntry> entries, String actorExternalId) {
        UUID transactionId = UUID.randomUUID();
        UUID commitId = UUID.randomUUID();
        coordinator.prepare(entries, transactionId, actorExternalId);
        coordinator.commit(transactionId, commitId);
    }
}

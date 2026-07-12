package org.ntrloc.graph.db.mutation;

import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.ledger.LinkUpdateEntry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Turns a client-facing MutationRequest into concrete LedgerEntry objects (resolving refId
// local references to real ids along the way) and drives them through the coordinator as one
// atomic unit. No schema/permission validation here -- that's a separate, not-yet-built
// component (Section 12); this only does the structural resolution needed to construct
// well-formed entries.
@Component
public class MutationRequestProcessor {

    private final LedgerRegisterCoordinator coordinator;

    public MutationRequestProcessor(LedgerRegisterCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Transactional
    public MutationResponse process(MutationRequest request) {
        UUID transactionId = UUID.randomUUID();
        UUID commitId = UUID.randomUUID();

        Map<String, UUID> refIdToItemId = new HashMap<>();
        List<LedgerEntry> entries = new ArrayList<>();
        List<ItemMutationResult> itemResults = new ArrayList<>();
        List<LinkMutationResult> linkResults = new ArrayList<>();

        // Items before links: link endpoint resolution needs local (refId) references already
        // assigned real ids.
        for (ItemMutation mutation : request.items()) {
            switch (mutation) {
                case ItemCreateMutation m -> {
                    UUID itemId = UUID.randomUUID();
                    if (m.refId() != null) refIdToItemId.put(m.refId(), itemId);
                    entries.add(new ItemCreateEntry(itemId, m.itemTypeId(), m.properties()));
                    itemResults.add(new ItemMutationResult(m.refId(), itemId, MutationOperation.CREATE));
                }
                case ItemUpdateMutation m -> {
                    entries.add(new ItemUpdateEntry(m.itemId(), m.properties()));
                    itemResults.add(new ItemMutationResult(null, m.itemId(), MutationOperation.UPDATE));
                }
                case ItemDeleteMutation m -> {
                    entries.add(new ItemDeleteEntry(m.itemId()));
                    itemResults.add(new ItemMutationResult(null, m.itemId(), MutationOperation.DELETE));
                }
            }
        }

        for (LinkMutation mutation : request.links()) {
            switch (mutation) {
                case LinkCreateMutation m -> {
                    UUID linkId = UUID.randomUUID();
                    List<LinkEndpoint> endpoints = m.endpoints().stream()
                            .map(ep -> new LinkEndpoint(ep.perspectiveId(), resolveItemReference(ep.item(), refIdToItemId)))
                            .toList();
                    entries.add(new LinkCreateEntry(linkId, m.linkTypeId(), endpoints, m.properties()));
                    linkResults.add(new LinkMutationResult(linkId, MutationOperation.CREATE));
                }
                case LinkUpdateMutation m -> {
                    entries.add(new LinkUpdateEntry(m.linkId(), m.properties()));
                    linkResults.add(new LinkMutationResult(m.linkId(), MutationOperation.UPDATE));
                }
                case LinkDeleteMutation m -> {
                    entries.add(new LinkDeleteEntry(m.linkId()));
                    linkResults.add(new LinkMutationResult(m.linkId(), MutationOperation.DELETE));
                }
            }
        }

        coordinator.prepare(entries, transactionId);
        coordinator.commit(transactionId, commitId);

        return new MutationResponse(itemResults, linkResults);
    }

    private UUID resolveItemReference(ItemReference reference, Map<String, UUID> refIdToItemId) {
        return switch (reference) {
            case NewItemReference r -> {
                UUID resolved = refIdToItemId.get(r.refId());
                if (resolved == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown refId: " + r.refId());
                }
                yield resolved;
            }
            case ExistingItemReference r -> r.itemId();
        };
    }
}

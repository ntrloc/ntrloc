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
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemLinkPerspectiveView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Turns a client-facing MutationRequest -- which speaks only in names (item type, property,
// perspective), never internal schema ids -- into concrete LedgerEntry objects (which speak
// only in ids), resolving refIds and all name lookups along the way. Drives the result through
// the coordinator as one atomic unit. No schema/permission validation here -- that's a separate,
// not-yet-built component (Section 12); this only does the structural resolution needed to
// construct well-formed entries.
@Component
public class MutationRequestProcessor {

    private final LedgerRegisterCoordinator coordinator;
    private final RegisterPartitionManager registerPartitionManager;
    private final SchemaManager schemaManager;

    public MutationRequestProcessor(LedgerRegisterCoordinator coordinator,
                                     RegisterPartitionManager registerPartitionManager,
                                     SchemaManager schemaManager) {
        this.coordinator = coordinator;
        this.registerPartitionManager = registerPartitionManager;
        this.schemaManager = schemaManager;
    }

    @Transactional
    public MutationResponse process(MutationRequest request) {
        UUID transactionId = UUID.randomUUID();
        UUID commitId = UUID.randomUUID();

        Map<String, UUID> refIdToItemId = new HashMap<>();
        Map<String, UUID> refIdToItemTypeId = new HashMap<>();
        List<LedgerEntry> entries = new ArrayList<>();
        List<ItemMutationResult> itemResults = new ArrayList<>();
        List<LinkMutationResult> linkResults = new ArrayList<>();

        // Items before links: link endpoint resolution needs new (refId) references already
        // assigned real ids and known types.
        for (ItemMutation mutation : request.items()) {
            switch (mutation) {
                case ItemCreateMutation m -> {
                    UUID itemTypeId = resolveItemTypeId(m.itemTypeName());
                    UUID itemId = UUID.randomUUID();
                    if (m.refId() != null) {
                        refIdToItemId.put(m.refId(), itemId);
                        refIdToItemTypeId.put(m.refId(), itemTypeId);
                    }
                    entries.add(new ItemCreateEntry(itemId, itemTypeId, resolveItemPropertyIds(itemTypeId, m.properties())));
                    itemResults.add(new ItemMutationResult(m.refId(), itemId, MutationOperation.CREATE));
                }
                case ItemUpdateMutation m -> {
                    UUID itemTypeId = registerPartitionManager.findItemTypeId(m.itemId());
                    entries.add(new ItemUpdateEntry(m.itemId(), resolveItemPropertyIds(itemTypeId, m.properties())));
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
                    ResolvedEndpoint a = resolveEndpoint(m.firstItem(), refIdToItemId, refIdToItemTypeId);
                    ResolvedEndpoint b = resolveEndpoint(m.secondItem(), refIdToItemId, refIdToItemTypeId);
                    UUID linkTypeId = resolveLinkTypeId(a, b);

                    UUID linkId = UUID.randomUUID();
                    LinkEndpoint endpointA = new LinkEndpoint(a.perspectiveIdFor(linkTypeId), a.itemId());
                    LinkEndpoint endpointB = new LinkEndpoint(b.perspectiveIdFor(linkTypeId), b.itemId());
                    entries.add(new LinkCreateEntry(linkId, linkTypeId, endpointA, endpointB,
                            resolveLinkPropertyIds(linkTypeId, m.properties())));
                    linkResults.add(new LinkMutationResult(linkId, MutationOperation.CREATE));
                }
                case LinkUpdateMutation m -> {
                    UUID linkTypeId = registerPartitionManager.findLinkTypeId(m.linkId());
                    entries.add(new LinkUpdateEntry(m.linkId(), resolveLinkPropertyIds(linkTypeId, m.properties())));
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

    // Mirrors EntityManagerImpl.resolveItemTypeId -- the one existing name->id precedent in
    // this codebase, on the read side.
    private UUID resolveItemTypeId(String itemTypeName) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.name().equals(itemTypeName))
                .map(item -> item.id())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown item type: " + itemTypeName));
    }

    private record ResolvedEndpoint(UUID itemId, UUID itemTypeId, String perspectiveName,
                                     List<AdminItemLinkPerspectiveView> candidates) {
        Set<UUID> candidateLinkTypeIds() {
            return candidates.stream().map(AdminItemLinkPerspectiveView::linkId).collect(Collectors.toSet());
        }

        UUID perspectiveIdFor(UUID linkTypeId) {
            return candidates.stream()
                    .filter(c -> c.linkId().equals(linkTypeId))
                    .map(AdminItemLinkPerspectiveView::id)
                    .findFirst()
                    .orElseThrow();
        }
    }

    private ResolvedEndpoint resolveEndpoint(LinkEndpointReference ref, Map<String, UUID> refIdToItemId, Map<String, UUID> refIdToItemTypeId) {
        UUID itemId;
        UUID itemTypeId;
        switch (ref.item()) {
            case NewItemReference r -> {
                itemId = refIdToItemId.get(r.refId());
                itemTypeId = refIdToItemTypeId.get(r.refId());
                if (itemId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown refId: " + r.refId());
            }
            case ExistingItemReference r -> {
                itemId = r.itemId();
                itemTypeId = registerPartitionManager.findItemTypeId(itemId);
            }
            default -> throw new IllegalStateException();
        }

        List<AdminItemLinkPerspectiveView> candidates = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> item.links().getOrDefault(ref.perspectiveName(), List.of()))
                .orElse(List.of());
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown perspective: " + ref.perspectiveName());
        }
        return new ResolvedEndpoint(itemId, itemTypeId, ref.perspectiveName(), candidates);
    }

    // A perspective name isn't unique per item type (the schema allows the same name across
    // multiple distinct link definitions), so the concrete link type is whichever one both
    // endpoints' perspective candidates agree on.
    private UUID resolveLinkTypeId(ResolvedEndpoint a, ResolvedEndpoint b) {
        Set<UUID> common = new HashSet<>(a.candidateLinkTypeIds());
        common.retainAll(b.candidateLinkTypeIds());
        if (common.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No link connects perspective '%s' to perspective '%s'".formatted(a.perspectiveName(), b.perspectiveName()));
        }
        if (common.size() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ambiguous link: multiple link types connect perspective '%s' to perspective '%s'".formatted(a.perspectiveName(), b.perspectiveName()));
        }
        return common.iterator().next();
    }

    private Map<UUID, Object> resolveItemPropertyIds(UUID itemTypeId, Map<String, Object> propertiesByName) {
        Map<String, UUID> nameToId = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> item.properties().stream()
                        .collect(Collectors.toMap(AdminPropertyDefinitionView::name, AdminPropertyDefinitionView::id)))
                .orElse(Map.of());
        return resolvePropertyIds(propertiesByName, nameToId);
    }

    private Map<UUID, Object> resolveLinkPropertyIds(UUID linkTypeId, Map<String, Object> propertiesByName) {
        Map<String, UUID> nameToId = schemaManager.getAdminSchema().links().stream()
                .filter(link -> link.id().equals(linkTypeId))
                .findFirst()
                .map(link -> link.properties().stream()
                        .collect(Collectors.toMap(AdminPropertyDefinitionView::name, AdminPropertyDefinitionView::id)))
                .orElse(Map.of());
        return resolvePropertyIds(propertiesByName, nameToId);
    }

    private Map<UUID, Object> resolvePropertyIds(Map<String, Object> propertiesByName, Map<String, UUID> nameToId) {
        Map<UUID, Object> byId = new HashMap<>();
        propertiesByName.forEach((name, value) -> {
            UUID id = nameToId.get(name);
            if (id == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown property: " + name);
            }
            byId.put(id, value);
        });
        return byId;
    }
}

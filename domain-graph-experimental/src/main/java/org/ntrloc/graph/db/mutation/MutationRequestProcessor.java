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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Turns a client-facing MutationRequest -- which speaks only in names (item type, property,
// perspective), never internal schema ids -- into concrete LedgerEntry objects (which speak
// only in ids), resolving refIds and all name lookups along the way. Drives the result through
// the coordinator as one atomic unit.
//
// Validates the whole request before touching anything: every mutation is checked and every
// resolvable reference is resolved in one pass, accumulating a ValidationError per bad reference
// rather than failing on the first one. If anything failed, MutationValidationException carries
// the complete list and the coordinator is never called -- nothing has been written yet at that
// point (schema lookups are all against SchemaManager's in-memory cache), so there's nothing to
// roll back. Permission checks are a separate, not-yet-built component (Section 12).
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
        List<ValidationError> errors = new ArrayList<>();
        Map<String, UUID> refIdToItemId = new HashMap<>();
        Map<String, UUID> refIdToItemTypeId = new HashMap<>();
        List<LedgerEntry> entries = new ArrayList<>();
        List<ItemMutationResult> itemResults = new ArrayList<>();
        List<LinkMutationResult> linkResults = new ArrayList<>();

        List<ItemMutation> items = request.items() == null ? List.of() : request.items();
        List<LinkMutation> links = request.links() == null ? List.of() : request.links();

        // Items before links: link endpoint resolution needs new (refId) references already
        // assigned real ids and known types.
        for (int i = 0; i < items.size(); i++) {
            String path = "items[%d]".formatted(i);
            switch (items.get(i)) {
                case ItemCreateMutation m -> processItemCreate(m, path, refIdToItemId, refIdToItemTypeId, entries, itemResults, errors);
                case ItemUpdateMutation m -> processItemUpdate(m, path, entries, itemResults, errors);
                case ItemDeleteMutation m -> {
                    entries.add(new ItemDeleteEntry(m.itemId()));
                    itemResults.add(new ItemMutationResult(null, m.itemId(), MutationOperation.DELETE));
                }
            }
        }

        for (int i = 0; i < links.size(); i++) {
            String path = "links[%d]".formatted(i);
            switch (links.get(i)) {
                case LinkCreateMutation m -> processLinkCreate(m, path, refIdToItemId, refIdToItemTypeId, entries, linkResults, errors);
                case LinkUpdateMutation m -> processLinkUpdate(m, path, entries, linkResults, errors);
                case LinkDeleteMutation m -> {
                    entries.add(new LinkDeleteEntry(m.linkId()));
                    linkResults.add(new LinkMutationResult(m.linkId(), MutationOperation.DELETE));
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new MutationValidationException(errors);
        }

        UUID transactionId = UUID.randomUUID();
        UUID commitId = UUID.randomUUID();
        coordinator.prepare(entries, transactionId);
        coordinator.commit(transactionId, commitId);

        return new MutationResponse(itemResults, linkResults);
    }

    private void processItemCreate(ItemCreateMutation m, String path, Map<String, UUID> refIdToItemId, Map<String, UUID> refIdToItemTypeId,
                                    List<LedgerEntry> entries, List<ItemMutationResult> itemResults, List<ValidationError> errors) {
        Optional<UUID> itemTypeId = findItemTypeIdByName(m.itemTypeName());
        if (itemTypeId.isEmpty()) {
            errors.add(new ValidationError(path + ".itemTypeName", "Unknown item type: " + m.itemTypeName()));
            return;
        }
        UUID itemId = UUID.randomUUID();
        if (m.refId() != null) {
            refIdToItemId.put(m.refId(), itemId);
            refIdToItemTypeId.put(m.refId(), itemTypeId.get());
        }
        Map<UUID, Object> properties = resolveItemPropertyIds(itemTypeId.get(), m.properties(), path + ".properties", errors);
        entries.add(new ItemCreateEntry(itemId, itemTypeId.get(), properties));
        itemResults.add(new ItemMutationResult(m.refId(), itemId, MutationOperation.CREATE));
    }

    private void processItemUpdate(ItemUpdateMutation m, String path, List<LedgerEntry> entries,
                                    List<ItemMutationResult> itemResults, List<ValidationError> errors) {
        Optional<UUID> itemTypeId = registerPartitionManager.findItemTypeId(m.itemId());
        if (itemTypeId.isEmpty()) {
            errors.add(new ValidationError(path + ".itemId", "Unknown item: " + m.itemId()));
            return;
        }
        Map<UUID, Object> properties = resolveItemPropertyIds(itemTypeId.get(), m.properties(), path + ".properties", errors);
        entries.add(new ItemUpdateEntry(m.itemId(), properties));
        itemResults.add(new ItemMutationResult(null, m.itemId(), MutationOperation.UPDATE));
    }

    private void processLinkCreate(LinkCreateMutation m, String path, Map<String, UUID> refIdToItemId, Map<String, UUID> refIdToItemTypeId,
                                    List<LedgerEntry> entries, List<LinkMutationResult> linkResults, List<ValidationError> errors) {
        Optional<ResolvedEndpoint> a = resolveEndpoint(m.firstItem(), path + ".firstItem", refIdToItemId, refIdToItemTypeId, errors);
        Optional<ResolvedEndpoint> b = resolveEndpoint(m.secondItem(), path + ".secondItem", refIdToItemId, refIdToItemTypeId, errors);
        if (a.isEmpty() || b.isEmpty()) return;

        Optional<UUID> linkTypeId = resolveLinkTypeId(a.get(), b.get(), path, errors);
        if (linkTypeId.isEmpty()) return;

        UUID linkId = UUID.randomUUID();
        LinkEndpoint endpointA = new LinkEndpoint(a.get().perspectiveIdFor(linkTypeId.get()), a.get().itemId());
        LinkEndpoint endpointB = new LinkEndpoint(b.get().perspectiveIdFor(linkTypeId.get()), b.get().itemId());
        Map<UUID, Object> properties = resolveLinkPropertyIds(linkTypeId.get(), m.properties(), path + ".properties", errors);
        entries.add(new LinkCreateEntry(linkId, linkTypeId.get(), endpointA, endpointB, properties));
        linkResults.add(new LinkMutationResult(linkId, MutationOperation.CREATE));
    }

    private void processLinkUpdate(LinkUpdateMutation m, String path, List<LedgerEntry> entries,
                                    List<LinkMutationResult> linkResults, List<ValidationError> errors) {
        Optional<UUID> linkTypeId = registerPartitionManager.findLinkTypeId(m.linkId());
        if (linkTypeId.isEmpty()) {
            errors.add(new ValidationError(path + ".linkId", "Unknown link: " + m.linkId()));
            return;
        }
        Map<UUID, Object> properties = resolveLinkPropertyIds(linkTypeId.get(), m.properties(), path + ".properties", errors);
        entries.add(new LinkUpdateEntry(m.linkId(), properties));
        linkResults.add(new LinkMutationResult(m.linkId(), MutationOperation.UPDATE));
    }

    // Mirrors EntityManagerImpl.resolveItemTypeId -- the one existing name->id precedent in
    // this codebase, on the read side.
    private Optional<UUID> findItemTypeIdByName(String itemTypeName) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.name().equals(itemTypeName))
                .map(item -> item.id())
                .findFirst();
    }

    private record ResolvedEndpoint(UUID itemId, String perspectiveName, List<AdminItemLinkPerspectiveView> candidates) {
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

    private Optional<ResolvedEndpoint> resolveEndpoint(LinkEndpointReference ref, String path,
                                                         Map<String, UUID> refIdToItemId, Map<String, UUID> refIdToItemTypeId,
                                                         List<ValidationError> errors) {
        UUID itemId;
        UUID itemTypeId;
        switch (ref.item()) {
            case NewItemReference r -> {
                itemId = refIdToItemId.get(r.refId());
                itemTypeId = refIdToItemTypeId.get(r.refId());
                if (itemId == null) {
                    errors.add(new ValidationError(path + ".item.refId", "Unknown refId: " + r.refId()));
                    return Optional.empty();
                }
            }
            case ExistingItemReference r -> {
                Optional<UUID> resolved = registerPartitionManager.findItemTypeId(r.itemId());
                if (resolved.isEmpty()) {
                    errors.add(new ValidationError(path + ".item.itemId", "Unknown item: " + r.itemId()));
                    return Optional.empty();
                }
                itemId = r.itemId();
                itemTypeId = resolved.get();
            }
        }

        List<AdminItemLinkPerspectiveView> candidates = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> item.links().getOrDefault(ref.perspectiveName(), List.of()))
                .orElse(List.of());
        if (candidates.isEmpty()) {
            errors.add(new ValidationError(path + ".perspectiveName", "Unknown perspective: " + ref.perspectiveName()));
            return Optional.empty();
        }
        return Optional.of(new ResolvedEndpoint(itemId, ref.perspectiveName(), candidates));
    }

    // A perspective name isn't unique per item type (the schema allows the same name across
    // multiple distinct link definitions), so the concrete link type is whichever one both
    // endpoints' perspective candidates agree on.
    private Optional<UUID> resolveLinkTypeId(ResolvedEndpoint a, ResolvedEndpoint b, String path, List<ValidationError> errors) {
        Set<UUID> common = new HashSet<>(a.candidateLinkTypeIds());
        common.retainAll(b.candidateLinkTypeIds());
        if (common.isEmpty()) {
            errors.add(new ValidationError(path,
                    "No link connects perspective '%s' to perspective '%s'".formatted(a.perspectiveName(), b.perspectiveName())));
            return Optional.empty();
        }
        if (common.size() > 1) {
            errors.add(new ValidationError(path,
                    "Ambiguous link: multiple link types connect perspective '%s' to perspective '%s'".formatted(a.perspectiveName(), b.perspectiveName())));
            return Optional.empty();
        }
        return Optional.of(common.iterator().next());
    }

    private Map<UUID, Object> resolveItemPropertyIds(UUID itemTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        Map<String, UUID> nameToId = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> item.properties().stream()
                        .collect(Collectors.toMap(AdminPropertyDefinitionView::name, AdminPropertyDefinitionView::id)))
                .orElse(Map.of());
        return resolvePropertyIds(propertiesByName, nameToId, path, errors);
    }

    private Map<UUID, Object> resolveLinkPropertyIds(UUID linkTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        Map<String, UUID> nameToId = schemaManager.getAdminSchema().links().stream()
                .filter(link -> link.id().equals(linkTypeId))
                .findFirst()
                .map(link -> link.properties().stream()
                        .collect(Collectors.toMap(AdminPropertyDefinitionView::name, AdminPropertyDefinitionView::id)))
                .orElse(Map.of());
        return resolvePropertyIds(propertiesByName, nameToId, path, errors);
    }

    private Map<UUID, Object> resolvePropertyIds(Map<String, Object> propertiesByName, Map<String, UUID> nameToId, String path, List<ValidationError> errors) {
        Map<UUID, Object> byId = new HashMap<>();
        if (propertiesByName == null) return byId;
        propertiesByName.forEach((name, value) -> {
            UUID id = nameToId.get(name);
            if (id == null) {
                errors.add(new ValidationError(path + "." + name, "Unknown property: " + name));
            } else {
                byId.put(id, value);
            }
        });
        return byId;
    }
}

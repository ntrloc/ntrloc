package org.ntrloc.graph.mutation;

import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.authorization.RequestPermissionContext;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.ledger.LinkUpdateEntry;
import org.ntrloc.graph.db.partition.register.RegisterLinkEndpoint;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemLinkPerspectiveView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
// roll back. Permission checks live in this same accumulation (see checkCanCreateItemType and
// friends) -- a denial is a mutation rejection like any other, not a separate mechanism.
//
// process() demands a real, non-null principal via @PreAuthorize (see
// docs/ntrloc-principal-enforcement-plan.md, Part C) -- this is a top-level component
// independently reachable from EntityManagerImpl, so it carries its own guard. Every private
// helper below that threads a principal through (processItemCreate, checkCanWriteItemProperties,
// etc.) is only ever reachable from process() itself (confirmed: no other caller exists), so a
// null principal can never reach them -- they take a plain NtrlocPrincipal, not @Nullable, and
// have no null-check of their own; the guard upstream is the only one that exists or needs to.
@Component
public class MutationRequestProcessor {

    private static final String PROPERTIES_PATH_SUFFIX = ".properties";
    private static final String PROPERTY_QUOTE_PREFIX = "Property '";
    private static final String ITEM_TYPE_NAME_PATH_SUFFIX = ".itemTypeName";

    private final LedgerRegisterCoordinator coordinator;
    private final RegisterPartitionManager registerPartitionManager;
    private final SchemaManager schemaManager;
    private final BinaryPartitionManager binaryPartitionManager;
    private final ControlledListManager controlledListManager;
    private final PermissionService permissionService;

    public MutationRequestProcessor(LedgerRegisterCoordinator coordinator,
                                     RegisterPartitionManager registerPartitionManager,
                                     SchemaManager schemaManager,
                                     BinaryPartitionManager binaryPartitionManager,
                                     ControlledListManager controlledListManager,
                                     PermissionService permissionService) {
        this.coordinator = coordinator;
        this.registerPartitionManager = registerPartitionManager;
        this.schemaManager = schemaManager;
        this.binaryPartitionManager = binaryPartitionManager;
        this.controlledListManager = controlledListManager;
        this.permissionService = permissionService;
    }

    @Transactional
    @PreAuthorize("#principal != null")
    public MutationResponse process(MutationRequest request, NtrlocPrincipal principal) {
        List<ValidationError> errors = new ArrayList<>();
        Map<String, UUID> refIdToItemId = new HashMap<>();
        Map<String, UUID> refIdToItemTypeId = new HashMap<>();
        List<LedgerEntry> entries = new ArrayList<>();
        List<ItemMutationResult> itemResults = new ArrayList<>();
        List<LinkMutationResult> linkResults = new ArrayList<>();
        // Perspective cardinality can only be judged once the *whole* request has been walked -- a
        // new item's own minCardinality can only ever be satisfied by link creates elsewhere in this
        // same request (an ItemCreateMutation carries no links of its own), so this accumulates a net
        // delta per (itemId, perspectiveId) across every link create/delete as they're processed, and
        // checkLinkPerspectiveCardinality judges the total only after that loop finishes.
        Map<UUID, NewItemInfo> newItems = new HashMap<>();
        Map<LinkPerspectiveKey, Integer> perspectiveDelta = new HashMap<>();
        Map<LinkPerspectiveKey, String> lastLinkMutationPath = new HashMap<>();

        List<ItemMutation> items = request.items() == null ? List.of() : request.items();
        List<LinkMutation> links = request.links() == null ? List.of() : request.links();

        // Items before links: link endpoint resolution needs new (refId) references already
        // assigned real ids and known types.
        for (int i = 0; i < items.size(); i++) {
            String path = "items[%d]".formatted(i);
            ItemMutation item = items.get(i);
            if (item instanceof ItemCreateMutation m) {
                processItemCreate(m, path, principal, refIdToItemId, refIdToItemTypeId, newItems, entries, itemResults, errors);
            } else if (item instanceof ItemUpdateMutation m) {
                processItemUpdate(m, path, principal, entries, itemResults, errors);
            } else if (item instanceof ItemDeleteMutation m) {
                processItemDelete(m, path, principal, entries, itemResults, errors);
            }
        }

        for (int i = 0; i < links.size(); i++) {
            String path = "links[%d]".formatted(i);
            LinkMutation link = links.get(i);
            if (link instanceof LinkCreateMutation m) {
                processLinkCreate(m, path, principal, refIdToItemId, refIdToItemTypeId, perspectiveDelta, lastLinkMutationPath, entries, linkResults, errors);
            } else if (link instanceof LinkUpdateMutation m) {
                processLinkUpdate(m, path, principal, entries, linkResults, errors);
            } else if (link instanceof LinkDeleteMutation m) {
                processLinkDelete(m, path, principal, perspectiveDelta, lastLinkMutationPath, entries, linkResults, errors);
            }
        }

        checkLinkPerspectiveCardinality(perspectiveDelta, newItems, lastLinkMutationPath, errors);

        if (!errors.isEmpty()) {
            throw new MutationValidationException(errors);
        }

        UUID transactionId = UUID.randomUUID();
        UUID commitId = UUID.randomUUID();
        // principal is guaranteed non-null here (process()'s own @PreAuthorize guard) -- this is
        // also ledger attribution (who made this change), not just a permission-check artifact.
        // One OffsetDateTime generated here, not left to each write's own NOW(): this exact value
        // becomes both the ledger entries' created_date and the register rows' resulting
        // created_date/updated_date (see LedgerRegisterCoordinatorImpl.prepare).
        UUID actorUserId = principal.id();
        OffsetDateTime createdDate = OffsetDateTime.now();
        // prepare() now durably commits on its own (see LedgerRegisterCoordinatorImpl's
        // REQUIRES_NEW) regardless of which branch runs next -- commit=false intentionally stops
        // here, leaving a genuinely prepared, independently-recoverable transaction for a later
        // confirmCommit() call, not a half-finished one.
        coordinator.prepare(entries, transactionId, actorUserId, createdDate);
        if (request.commit() == null || request.commit()) {
            coordinator.commit(transactionId, commitId);
        }

        return new MutationResponse(itemResults, linkResults, transactionId);
    }

    // Confirms a transaction previously prepared with commit=false. Generates its own commitId --
    // callers never need to track one themselves, same as the synchronous path in process().
    @Transactional
    public CommitResponse confirmCommit(UUID transactionId) {
        coordinator.commit(transactionId, UUID.randomUUID());
        return new CommitResponse(transactionId);
    }

    // Discards a transaction previously prepared with commit=false -- its UNCOMMITTED ledger entries
    // and staged register rows are deleted outright, not just marked aborted (see
    // LedgerRegisterCoordinator.abort's own doc comment).
    @Transactional
    public AbortResponse confirmAbort(UUID transactionId) {
        coordinator.abort(transactionId);
        return new AbortResponse(transactionId);
    }

    private void processItemCreate(ItemCreateMutation m, String path, NtrlocPrincipal principal,
                                    Map<String, UUID> refIdToItemId, Map<String, UUID> refIdToItemTypeId,
                                    Map<UUID, NewItemInfo> newItems,
                                    List<LedgerEntry> entries, List<ItemMutationResult> itemResults, List<ValidationError> errors) {
        Optional<UUID> itemTypeId = findItemTypeIdByName(m.itemTypeName());
        if (itemTypeId.isEmpty()) {
            errors.add(new ValidationError(path + ITEM_TYPE_NAME_PATH_SUFFIX, "Unknown item type: " + m.itemTypeName()));
            return;
        }
        if (isAbstractItemType(itemTypeId.get())) {
            errors.add(new ValidationError(path + ITEM_TYPE_NAME_PATH_SUFFIX, "Cannot create an instance of abstract item type: " + m.itemTypeName()));
            return;
        }
        if (!permissionService.canCreateItemType(principal, itemTypeId.get())) {
            errors.add(new ValidationError(path + ITEM_TYPE_NAME_PATH_SUFFIX, "Not permitted to create items of type: " + m.itemTypeName()));
            return;
        }
        UUID itemId = UUID.randomUUID();
        // Tracked regardless of refId -- checkLinkPerspectiveCardinality needs to know this item has
        // no pre-existing links (baseline zero) even when nothing in this request ever references it
        // by refId (e.g. it's simply missing a link its own minCardinality requires).
        newItems.put(itemId, new NewItemInfo(path, itemTypeId.get()));
        if (m.refId() != null) {
            refIdToItemId.put(m.refId(), itemId);
            refIdToItemTypeId.put(m.refId(), itemTypeId.get());
        }
        Map<UUID, Object> properties = resolveItemPropertyIds(itemTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        // Create only, never update -- see checkRequiredItemProperties' own comment on why an
        // update's partial-diff payload can't be checked the same way.
        checkRequiredItemProperties(itemTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        checkDeprecatedItemProperties(itemTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        entries.add(new ItemCreateEntry(itemId, itemTypeId.get(), properties, Map.of(), Set.of()));
        itemResults.add(new ItemMutationResult(m.refId(), itemId, MutationOperation.CREATE));
    }

    private void processItemUpdate(ItemUpdateMutation m, String path, NtrlocPrincipal principal, List<LedgerEntry> entries,
                                    List<ItemMutationResult> itemResults, List<ValidationError> errors) {
        Optional<UUID> itemTypeId = registerPartitionManager.findItemTypeId(m.itemId());
        if (itemTypeId.isEmpty()) {
            errors.add(new ValidationError(path + ".itemId", "Unknown item: " + m.itemId()));
            return;
        }
        // Property-write is marker-mediated, so it only ever applies here (an existing item, which
        // can already carry markers) -- never on create, where the item (and any markers on it)
        // doesn't exist yet. item-type:create alone governs what create-time properties are set.
        checkCanWriteItemProperties(principal, itemTypeId.get(), m.itemId(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        Map<UUID, Object> properties = resolveItemPropertyIds(itemTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        // The schema can require a property today that didn't exist, or wasn't REQUIRED, when this
        // item was created -- see checkRequiredPropertiesById's own comment. Checked against the
        // item's own *resulting* state (current register row merged with this diff), not just the
        // diff in isolation, so a mutation that never mentions the property at all still has to
        // fail if the item would still be missing it afterward.
        checkRequiredItemPropertiesOnUpdate(itemTypeId.get(), m.itemId(), properties, path + PROPERTIES_PATH_SUFFIX, errors);
        checkDeprecatedItemProperties(itemTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        entries.add(new ItemUpdateEntry(m.itemId(), properties, Map.of(), Set.of(), Set.of(), Set.of()));
        itemResults.add(new ItemMutationResult(null, m.itemId(), MutationOperation.UPDATE));
    }

    private void processItemDelete(ItemDeleteMutation m, String path, NtrlocPrincipal principal,
                                    List<LedgerEntry> entries, List<ItemMutationResult> itemResults, List<ValidationError> errors) {
        if (!canDeleteItem(principal, m.itemId())) {
            errors.add(new ValidationError(path, "Not permitted to delete this item"));
            return;
        }
        entries.add(new ItemDeleteEntry(m.itemId()));
        itemResults.add(new ItemMutationResult(null, m.itemId(), MutationOperation.DELETE));
    }

    private boolean canDeleteItem(NtrlocPrincipal principal, UUID itemId) {
        if (principal.isSuperuser()) return true;
        var ctx = permissionService.buildContext(principal);
        return permissionService.mayDeleteItem(ctx, permissionService.markerIdsForItem(itemId));
    }

    private boolean canCreateLink(NtrlocPrincipal principal, LinkEndpoint endpoint) {
        if (principal.isSuperuser()) return true;
        var ctx = permissionService.buildContext(principal);
        return permissionService.mayCreateLink(ctx, permissionService.markerIdsForItem(endpoint.itemId()), endpoint.perspectiveId());
    }

    private void processLinkCreate(LinkCreateMutation m, String path, NtrlocPrincipal principal,
                                    Map<String, UUID> refIdToItemId, Map<String, UUID> refIdToItemTypeId,
                                    Map<LinkPerspectiveKey, Integer> perspectiveDelta, Map<LinkPerspectiveKey, String> lastLinkMutationPath,
                                    List<LedgerEntry> entries, List<LinkMutationResult> linkResults, List<ValidationError> errors) {
        Optional<ResolvedEndpoint> a = resolveEndpoint(m.firstItem(), path + ".firstItem", refIdToItemId, refIdToItemTypeId, errors);
        Optional<ResolvedEndpoint> b = resolveEndpoint(m.secondItem(), path + ".secondItem", refIdToItemId, refIdToItemTypeId, errors);
        if (a.isEmpty() || b.isEmpty()) return;

        Optional<UUID> linkTypeId = resolveLinkTypeId(a.get(), b.get(), path, errors);
        if (linkTypeId.isEmpty()) return;

        UUID linkId = UUID.randomUUID();
        LinkEndpoint endpointA = new LinkEndpoint(a.get().perspectiveIdFor(linkTypeId.get()), a.get().itemId());
        LinkEndpoint endpointB = new LinkEndpoint(b.get().perspectiveIdFor(linkTypeId.get()), b.get().itemId());
        // Both sides' own link:create grant are required, not just one -- a user with rights on
        // only one endpoint's perspective can't unilaterally attach a link into the other side.
        if (!canCreateLink(principal, endpointA) || !canCreateLink(principal, endpointB)) {
            errors.add(new ValidationError(path, "Not permitted to create this link"));
            return;
        }
        recordLinkDelta(perspectiveDelta, lastLinkMutationPath, endpointA, path, +1);
        recordLinkDelta(perspectiveDelta, lastLinkMutationPath, endpointB, path, +1);
        checkCanWriteLinkProperties(principal, List.of(endpointA.itemId(), endpointB.itemId()), linkTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        Map<UUID, Object> properties = resolveLinkPropertyIds(linkTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        checkRequiredLinkProperties(linkTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        checkDeprecatedLinkProperties(linkTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        entries.add(new LinkCreateEntry(linkId, linkTypeId.get(), endpointA, endpointB, properties));
        linkResults.add(new LinkMutationResult(linkId, MutationOperation.CREATE));
    }

    private void processLinkUpdate(LinkUpdateMutation m, String path, NtrlocPrincipal principal, List<LedgerEntry> entries,
                                    List<LinkMutationResult> linkResults, List<ValidationError> errors) {
        Optional<UUID> linkTypeId = registerPartitionManager.findLinkTypeId(m.linkId());
        if (linkTypeId.isEmpty()) {
            errors.add(new ValidationError(path + ".linkId", "Unknown link: " + m.linkId()));
            return;
        }
        List<UUID> endpointItemIds = registerPartitionManager.findLinkEndpoints(m.linkId()).stream().map(RegisterLinkEndpoint::itemId).toList();
        checkCanWriteLinkProperties(principal, endpointItemIds, linkTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        Map<UUID, Object> properties = resolveLinkPropertyIds(linkTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        checkRequiredLinkPropertiesOnUpdate(linkTypeId.get(), m.linkId(), properties, path + PROPERTIES_PATH_SUFFIX, errors);
        checkDeprecatedLinkProperties(linkTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        entries.add(new LinkUpdateEntry(m.linkId(), properties));
        linkResults.add(new LinkMutationResult(m.linkId(), MutationOperation.UPDATE));
    }

    // An update never changes endpoints (Section 8 -- see stageLinkUpdate's own comment), so unlike
    // create/delete there is no perspective-cardinality delta to record here.
    private void processLinkDelete(LinkDeleteMutation m, String path, NtrlocPrincipal principal,
                                    Map<LinkPerspectiveKey, Integer> perspectiveDelta, Map<LinkPerspectiveKey, String> lastLinkMutationPath,
                                    List<LedgerEntry> entries, List<LinkMutationResult> linkResults, List<ValidationError> errors) {
        List<RegisterLinkEndpoint> endpoints = registerPartitionManager.findLinkEndpoints(m.linkId());
        // Both sides' own link:delete grant are required, same symmetric reasoning as
        // processLinkCreate's own link:create check.
        if (!canDeleteLink(principal, endpoints)) {
            errors.add(new ValidationError(path, "Not permitted to delete this link"));
            return;
        }
        for (RegisterLinkEndpoint endpoint : endpoints) {
            recordLinkDelta(perspectiveDelta, lastLinkMutationPath, endpoint, path, -1);
        }
        entries.add(new LinkDeleteEntry(m.linkId()));
        linkResults.add(new LinkMutationResult(m.linkId(), MutationOperation.DELETE));
    }

    private boolean canDeleteLink(NtrlocPrincipal principal, List<RegisterLinkEndpoint> endpoints) {
        if (principal.isSuperuser()) return true;
        var ctx = permissionService.buildContext(principal);
        for (RegisterLinkEndpoint endpoint : endpoints) {
            if (!permissionService.mayDeleteLink(ctx, permissionService.markerIdsForItem(endpoint.itemId()), endpoint.perspectiveId())) {
                return false;
            }
        }
        return true;
    }

    private record LinkPerspectiveKey(UUID itemId, UUID perspectiveId) {}

    private void recordLinkDelta(Map<LinkPerspectiveKey, Integer> perspectiveDelta, Map<LinkPerspectiveKey, String> lastLinkMutationPath,
                                  LinkEndpoint endpoint, String path, int change) {
        recordLinkDelta(perspectiveDelta, lastLinkMutationPath, new LinkPerspectiveKey(endpoint.itemId(), endpoint.perspectiveId()), path, change);
    }

    private void recordLinkDelta(Map<LinkPerspectiveKey, Integer> perspectiveDelta, Map<LinkPerspectiveKey, String> lastLinkMutationPath,
                                  RegisterLinkEndpoint endpoint, String path, int change) {
        recordLinkDelta(perspectiveDelta, lastLinkMutationPath, new LinkPerspectiveKey(endpoint.itemId(), endpoint.perspectiveId()), path, change);
    }

    private void recordLinkDelta(Map<LinkPerspectiveKey, Integer> perspectiveDelta, Map<LinkPerspectiveKey, String> lastLinkMutationPath,
                                  LinkPerspectiveKey key, String path, int change) {
        perspectiveDelta.merge(key, change, Integer::sum);
        lastLinkMutationPath.put(key, path);
    }

    // Where checkLinkPerspectiveCardinality learns a new item's own type from, to enumerate its
    // perspectives in the second pass below -- see that method's own comment.
    private record NewItemInfo(String path, UUID itemTypeId) {}

    // Judged only once the whole request has been walked -- see process()'s own comment on why a
    // per-mutation check can't work for minCardinality. Two passes: the first judges every
    // perspective some link create/delete in this request actually touched; the second catches a
    // new item's own perspective that nothing touched at all (zero link creates for it, so it never
    // becomes a perspectiveDelta key), which would otherwise slip past the first pass entirely.
    private void checkLinkPerspectiveCardinality(Map<LinkPerspectiveKey, Integer> perspectiveDelta, Map<UUID, NewItemInfo> newItems,
                                                  Map<LinkPerspectiveKey, String> lastLinkMutationPath, List<ValidationError> errors) {
        // A net-zero delta (e.g. one add and one remove against the same perspective in the same
        // request) is skipped outright: nothing is actually changing for that pair, so this must
        // stay silent even if its existing count already violates a bound tightened after the fact
        // -- same forward-only philosophy as the required-property checks (a schema/data change only
        // ever blocks a write that moves the needle, never retroactively flags one that doesn't).
        perspectiveDelta.forEach((key, change) -> {
            if (change == 0) return;
            PerspectiveLookup perspective = findPerspectiveById(key.perspectiveId()).orElseThrow();
            NewItemInfo newItem = newItems.get(key.itemId());
            int existing = newItem != null ? 0 : registerPartitionManager.countLinksForPerspective(key.itemId(), key.perspectiveId());
            String path = newItem != null ? newItem.path() + ".links." + perspective.name() : lastLinkMutationPath.get(key);
            reportCardinalityViolation(perspective, existing + change, path, errors);
        });

        newItems.forEach((itemId, info) -> {
            for (PerspectiveLookup perspective : perspectivesFor(info.itemTypeId())) {
                if (perspective.view().minCardinality() <= 0) continue;
                if (perspectiveDelta.containsKey(new LinkPerspectiveKey(itemId, perspective.view().id()))) continue;
                reportCardinalityViolation(perspective, 0, info.path() + ".links." + perspective.name(), errors);
            }
        });
    }

    // minCardinality is NOT NULL at the schema level (0 means "no minimum"), but maxCardinality is
    // genuinely nullable (no maximum at all).
    private void reportCardinalityViolation(PerspectiveLookup perspective, int finalCount, String path, List<ValidationError> errors) {
        if (finalCount < perspective.view().minCardinality()) {
            errors.add(new ValidationError(path, "Perspective '" + perspective.name() + "' requires a minimum of "
                    + perspective.view().minCardinality() + " link(s), but found " + finalCount));
        }
        if (perspective.view().maxCardinality() != null && finalCount > perspective.view().maxCardinality()) {
            errors.add(new ValidationError(path, "Perspective '" + perspective.name() + "' allows a maximum of "
                    + perspective.view().maxCardinality() + " link(s), but found " + finalCount));
        }
    }

    // A perspective's name lives only as its own key in AdminItemDefinitionView.links() (a
    // Map<String, List<AdminItemLinkPerspectiveView>>), not as a field on the view itself -- unlike
    // a property, which carries its own name directly -- so this has to keep the two paired together
    // while it searches, the same way resolveEndpoint's own perspective lookup stays scoped by name.
    private record PerspectiveLookup(String name, AdminItemLinkPerspectiveView view) {}

    private Optional<PerspectiveLookup> findPerspectiveById(UUID perspectiveId) {
        return schemaManager.getAdminSchema().items().stream()
                .flatMap(item -> perspectivesFor(item.id()).stream())
                .filter(pl -> pl.view().id().equals(perspectiveId))
                .findFirst();
    }

    private List<PerspectiveLookup> perspectivesFor(UUID itemTypeId) {
        Map<String, List<AdminItemLinkPerspectiveView>> links = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(AdminItemDefinitionView::links)
                .orElse(null);
        if (links == null) return List.of();
        return links.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(v -> new PerspectiveLookup(e.getKey(), v)))
                .toList();
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
        if (ref.item() instanceof NewItemReference r) {
            itemId = refIdToItemId.get(r.refId());
            itemTypeId = refIdToItemTypeId.get(r.refId());
            if (itemId == null) {
                errors.add(new ValidationError(path + ".item.refId", "Unknown refId: " + r.refId()));
                return Optional.empty();
            }
        } else if (ref.item() instanceof ExistingItemReference r) {
            Optional<UUID> resolved = registerPartitionManager.findItemTypeId(r.itemId());
            if (resolved.isEmpty()) {
                errors.add(new ValidationError(path + ".item.itemId", "Unknown item: " + r.itemId()));
                return Optional.empty();
            }
            itemId = r.itemId();
            itemTypeId = resolved.get();
        } else {
            throw new IllegalArgumentException("Unsupported item reference: " + ref.item().getClass().getSimpleName());
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

    // An abstract item type exists only to be extended -- items must be created as one of its
    // concrete subtypes instead. Checked here, not in SchemaMutationValidation, since this is an
    // item-instance write-path concern, not a schema-mutation one (see this class's ValidationError
    // convention vs. that class's IllegalArgumentException one).
    private boolean isAbstractItemType(UUID itemTypeId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(AdminItemDefinitionView::abstractType)
                .orElse(false);
    }

    // The names a payload can address at one level: leaf properties and property groups, which share
    // one namespace (sibling-name uniqueness is enforced in the schema). Trait contributions appear
    // as groups named for the trait, so a payload addresses them as {"File": {"name": ...}}.
    private record Scope(Map<String, AdminPropertyDefinitionView> properties, Map<String, AdminPropertyGroupView> groups) {
        static Scope of(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups) {
            return new Scope(
                    properties.stream().collect(Collectors.toMap(AdminPropertyDefinitionView::name, p -> p, (a, b) -> a)),
                    groups.stream().collect(Collectors.toMap(AdminPropertyGroupView::name, g -> g, (a, b) -> a)));
        }
    }

    private Scope itemScope(UUID itemTypeId) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> Scope.of(item.properties(), item.groups()))
                .orElse(Scope.of(List.of(), List.of()));
    }

    private Map<UUID, Object> resolveItemPropertyIds(UUID itemTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        return resolvePropertyIds(propertiesByName, itemScope(itemTypeId), path, errors);
    }

    private Scope linkScope(UUID linkTypeId) {
        return schemaManager.getAdminSchema().links().stream()
                .filter(link -> link.id().equals(linkTypeId))
                .findFirst()
                .map(link -> Scope.of(link.properties(), link.groups()))
                .orElse(Scope.of(List.of(), List.of()));
    }

    private Map<UUID, Object> resolveLinkPropertyIds(UUID linkTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        return resolvePropertyIds(propertiesByName, linkScope(linkTypeId), path, errors);
    }

    // Link counterparts to checkRequiredItemProperties/checkRequiredItemPropertiesOnUpdate/
    // checkDeprecatedItemProperties -- same checks, scoped via linkScope instead of itemScope, and
    // checkRequiredLinkPropertiesOnUpdate merges onto the link's own current register row
    // (resolveMergedLinkPropertiesById) rather than an item's.
    private void checkRequiredLinkProperties(UUID linkTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        checkRequiredProperties(linkScope(linkTypeId), propertiesByName, path, errors);
    }

    private void checkRequiredLinkPropertiesOnUpdate(UUID linkTypeId, UUID linkId, Map<UUID, Object> propertiesDiff, String path, List<ValidationError> errors) {
        Map<UUID, Object> merged = registerPartitionManager.resolveMergedLinkPropertiesById(linkId, linkTypeId, propertiesDiff);
        checkRequiredPropertiesById(linkScope(linkTypeId), merged, path, errors);
    }

    private void checkDeprecatedLinkProperties(UUID linkTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        checkDeprecatedProperties(linkScope(linkTypeId), propertiesByName, path, errors);
    }

    // Create only, never called for an update -- an update's own payload is a partial diff (a key
    // absent from it means "leave alone", not "clear"), so there is nothing to enforce there; an
    // explicit null on a REQUIRED property would leave the item invalid too, but that is a separate,
    // not-yet-built concern (resolvePropertyIds' own null branch is the update-clear sentinel and
    // deliberately never type- or presence-checked). Walks the item type's *full* schema shape, not
    // just the payload's own keys the way resolvePropertyIds does -- that is what lets this notice a
    // REQUIRED property the payload never mentions at all, which resolvePropertyIds structurally
    // cannot (it only ever iterates keys actually present). Recurses into groups the same way
    // resolveGroupValue does, so a REQUIRED property nested inside a group is enforced identically to
    // a top-level one.
    private void checkRequiredItemProperties(UUID itemTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        checkRequiredProperties(itemScope(itemTypeId), propertiesByName, path, errors);
    }

    // Absence only -- a key explicitly present with a null value is a different case (the
    // update-diff "clear this property" sentinel, rejected for a REQUIRED property by
    // resolvePropertyIds' own null branch instead, which this deliberately leaves alone so the two
    // never both fire for the same property at once).
    private void checkRequiredProperties(Scope scope, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        Map<String, Object> values = propertiesByName == null ? Map.of() : propertiesByName;
        for (AdminPropertyDefinitionView property : scope.properties().values()) {
            if (property.usage() != PropertyUsage.REQUIRED) continue;
            if (!values.containsKey(property.name())) {
                errors.add(new ValidationError(path + "." + property.name(), PROPERTY_QUOTE_PREFIX + property.name() + "' is required"));
            }
        }
        for (AdminPropertyGroupView group : scope.groups().values()) {
            Map<String, Object> nested = Map.of();
            if (values.get(group.name()) instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) rawMap;
                nested = cast;
            }
            checkRequiredProperties(Scope.of(group.properties(), group.groups()), nested, path + "." + group.name(), errors);
        }
    }

    // The schema can change after an item already exists -- a property that was OPTIONAL (or didn't
    // exist at all) when an item was created can become REQUIRED later, without that item being
    // retroactively touched or invalidated (same philosophy as isAbstractItemType's own one-way
    // check: a schema change only ever blocks new writes, never rewrites or deletes existing data).
    // But the next write to *this* item has to leave it satisfying the schema as it stands today, so
    // this checks the item's own *resulting* state -- registerPartitionManager's own current
    // register row merged with this update's diff (resolveMergedPropertiesById, the same merge
    // PipelineMarkerDecisionService.enrichUpdate uses to evaluate pipeline predicates against an
    // about-to-be-committed item) -- not just the diff in isolation the way
    // checkRequiredItemProperties checks a create payload. A property this update's own diff never
    // mentions at all still has to end up with a real value, which is exactly what merging onto the
    // current row (rather than just checking the diff) catches.
    private void checkRequiredItemPropertiesOnUpdate(UUID itemTypeId, UUID itemId, Map<UUID, Object> propertiesDiff, String path, List<ValidationError> errors) {
        Map<UUID, Object> merged = registerPartitionManager.resolveMergedPropertiesById(itemId, itemTypeId, propertiesDiff);
        checkRequiredPropertiesById(itemScope(itemTypeId), merged, path, errors);
    }

    // Property ids are globally unique regardless of which group (if any) they're nested under --
    // see resolveGroupValue's own "flat storage" comment -- so unlike checkRequiredProperties (which
    // has to narrow to each group's own name-scoped sub-map as it recurses), this only ever needs to
    // narrow the *schema* walk; valuesById itself stays the one flat map throughout.
    private void checkRequiredPropertiesById(Scope scope, Map<UUID, Object> valuesById, String path, List<ValidationError> errors) {
        for (AdminPropertyDefinitionView property : scope.properties().values()) {
            if (property.usage() != PropertyUsage.REQUIRED) continue;
            if (valuesById.get(property.id()) == null) {
                errors.add(new ValidationError(path + "." + property.name(), PROPERTY_QUOTE_PREFIX + property.name() + "' must be set"));
            }
        }
        for (AdminPropertyGroupView group : scope.groups().values()) {
            checkRequiredPropertiesById(Scope.of(group.properties(), group.groups()), valuesById, path + "." + group.name(), errors);
        }
    }

    // Presence, not absence -- the opposite of checkRequiredProperties, so this walks the payload's
    // own keys (the way resolvePropertyIds does) rather than the full schema shape: a deprecated
    // property the payload never mentions is fine. Applies to both create and update -- unlike
    // checkRequiredItemProperties, there's no partial-diff concern here, since this only ever reacts
    // to a key actually present in this specific payload. Recurses into groups the same way
    // resolveGroupValue does, so a DEPRECATED property nested inside a group is caught the same as a
    // top-level one.
    private void checkDeprecatedItemProperties(UUID itemTypeId, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        checkDeprecatedProperties(itemScope(itemTypeId), propertiesByName, path, errors);
    }

    private void checkDeprecatedProperties(Scope scope, Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        if (propertiesByName == null) return;
        propertiesByName.forEach((name, value) -> {
            String childPath = path + "." + name;
            AdminPropertyGroupView group = scope.groups().get(name);
            if (group != null) {
                if (value instanceof Map<?, ?> rawMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) rawMap;
                    checkDeprecatedProperties(Scope.of(group.properties(), group.groups()), nested, childPath, errors);
                }
                return;
            }
            AdminPropertyDefinitionView property = scope.properties().get(name);
            if (property != null && property.usage() == PropertyUsage.DEPRECATED) {
                errors.add(new ValidationError(childPath, PROPERTY_QUOTE_PREFIX + property.name() + "' is deprecated and cannot be set"));
            }
        });
    }

    // Update only -- see processItemUpdate's own comment on why property:write can't apply to
    // create. Walks the payload's own keys (the way checkDeprecatedProperties does), recursing
    // into groups the same way, since a property's write grant is per-leaf regardless of how deep
    // it's nested.
    private void checkCanWriteItemProperties(NtrlocPrincipal principal, UUID itemTypeId, UUID itemId,
                                              Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        if (propertiesByName == null || propertiesByName.isEmpty()) return;
        if (principal.isSuperuser()) return;
        var ctx = permissionService.buildContext(principal);
        Set<UUID> itemMarkerIds = permissionService.markerIdsForItem(itemId);
        checkCanWriteProperties(ctx, itemMarkerIds, itemScope(itemTypeId), propertiesByName, path, errors);
    }

    private void checkCanWriteProperties(RequestPermissionContext ctx, Set<UUID> itemMarkerIds, Scope scope,
                                          Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        propertiesByName.forEach((name, value) -> {
            String childPath = path + "." + name;
            AdminPropertyGroupView group = scope.groups().get(name);
            if (group != null) {
                if (value instanceof Map<?, ?> rawMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) rawMap;
                    checkCanWriteProperties(ctx, itemMarkerIds, Scope.of(group.properties(), group.groups()), nested, childPath, errors);
                }
                return;
            }
            AdminPropertyDefinitionView property = scope.properties().get(name);
            if (property != null && !permissionService.mayWriteProperty(ctx, itemMarkerIds, property.id())) {
                errors.add(new ValidationError(childPath, "Not permitted to write property: " + property.name()));
            }
        });
    }

    // Applies to both create and update, unlike checkCanWriteItemProperties -- a link's two
    // endpoint items already exist (and already carry whatever markers they carry) by the time
    // *any* link mutation runs, including create, since an endpoint is always either an existing
    // item or one created earlier in this same request. Both endpoints' own link_property:write
    // grant are required, same symmetric reasoning as link:create/delete.
    private void checkCanWriteLinkProperties(NtrlocPrincipal principal, List<UUID> endpointItemIds, UUID linkTypeId,
                                              Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        if (propertiesByName == null || propertiesByName.isEmpty()) return;
        if (principal.isSuperuser()) return;
        var ctx = permissionService.buildContext(principal);
        List<Set<UUID>> endpointMarkerSets = endpointItemIds.stream().map(permissionService::markerIdsForItem).toList();
        checkCanWriteLinkProperties(ctx, endpointMarkerSets, linkScope(linkTypeId), propertiesByName, path, errors);
    }

    private void checkCanWriteLinkProperties(RequestPermissionContext ctx, List<Set<UUID>> endpointMarkerSets, Scope scope,
                                               Map<String, Object> propertiesByName, String path, List<ValidationError> errors) {
        propertiesByName.forEach((name, value) -> {
            String childPath = path + "." + name;
            AdminPropertyGroupView group = scope.groups().get(name);
            if (group != null) {
                if (value instanceof Map<?, ?> rawMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) rawMap;
                    checkCanWriteLinkProperties(ctx, endpointMarkerSets, Scope.of(group.properties(), group.groups()), nested, childPath, errors);
                }
                return;
            }
            AdminPropertyDefinitionView property = scope.properties().get(name);
            if (property == null) return;
            boolean allEndpointsGranted = endpointMarkerSets.stream()
                    .allMatch(markerIds -> permissionService.mayWriteLinkProperty(ctx, markerIds, property.id()));
            if (!allEndpointsGranted) {
                errors.add(new ValidationError(childPath, "Not permitted to write link property: " + property.name()));
            }
        });
    }

    private Map<UUID, Object> resolvePropertyIds(Map<String, Object> propertiesByName, Scope scope, String path, List<ValidationError> errors) {
        Map<UUID, Object> byId = new HashMap<>();
        if (propertiesByName == null) return byId;
        propertiesByName.forEach((name, value) -> {
            String childPath = path + "." + name;
            AdminPropertyGroupView group = scope.groups().get(name);
            if (group != null) {
                resolveGroupValue(childPath, group, value, byId, errors);
                return;
            }
            AdminPropertyDefinitionView property = scope.properties().get(name);
            if (property == null) {
                errors.add(new ValidationError(childPath, "Unknown property: " + name));
                return;
            }
            // null is the update-diff "clear this property" sentinel -- never type-checked, but a
            // REQUIRED property can't be cleared this way either (it would leave the item invalid),
            // so this is the one check the sentinel still gets. Applies to a create payload's own
            // explicit null the same as an update's -- both mean the same thing here (see
            // checkRequiredProperties' own comment on why that method only ever covers absence).
            if (value == null) {
                if (property.usage() == PropertyUsage.REQUIRED) {
                    errors.add(new ValidationError(childPath, PROPERTY_QUOTE_PREFIX + property.name() + "' is required and cannot be set to null"));
                    return;
                }
                byId.put(property.id(), null);
                return;
            }
            int before = errors.size();
            validatePropertyValue(childPath, property, value, errors);
            if (errors.size() == before) {
                byId.put(property.id(), value);
            }
        });
        return byId;
    }

    // A group is structure, not a value: its payload is a nested object whose keys are resolved
    // against the group's *own* children, which is what lets two properties named the same thing
    // coexist under different groups -- the JSON nesting scopes the lookup. There is deliberately no
    // way to write to the group itself, so null (which would mean "clear everything under it") is a
    // validation error; clear the leaves you mean to clear.
    private void resolveGroupValue(String path, AdminPropertyGroupView group, Object value,
                                   Map<UUID, Object> byId, List<ValidationError> errors) {
        if (value == null) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + group.name() + "' is a property group and cannot be set to null; clear its properties individually"));
            return;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + group.name() + "' is a property group and expects an object value but got: " + describeValue(value)));
            return;
        }
        // JSON object keys are always strings -- Jackson never produces anything else when binding
        // a JSON object into a Map<String, Object>-typed field, the same assumption the top-level
        // ItemCreateMutation/ItemUpdateMutation properties maps already rely on.
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) rawMap;
        byId.putAll(resolvePropertyIds(nested, Scope.of(group.properties(), group.groups()), path, errors));
    }

    private void validatePropertyValue(String path, AdminPropertyDefinitionView property, Object value, List<ValidationError> errors) {
        if (property.type() == PropertyType.BINARY) {
            validateBinaryValue(path, property, value, errors);
            return;
        }
        if (property.cardinality() == PropertyCardinality.SINGLE) {
            validateScalar(path, property, value, errors);
            return;
        }
        if (!(value instanceof List<?> list)) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + property.name() + "' expects a list of values"));
            return;
        }
        if (property.cardinality() == PropertyCardinality.SET) {
            Set<Object> seen = new HashSet<>();
            for (Object element : list) {
                if (!seen.add(element)) {
                    errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + property.name() + "' contains a duplicate value: " + element));
                }
            }
        }
        for (int i = 0; i < list.size(); i++) {
            validateScalar(path + "[" + i + "]", property, list.get(i), errors);
        }
    }

    // A binary property's value is the id an earlier POST /api/binary/upload returned, not literal
    // content -- so validation here means confirming that id actually names stored binary content,
    // not type-checking a value the way validateScalar does for every other property type.
    private void validateBinaryValue(String path, AdminPropertyDefinitionView property, Object value, List<ValidationError> errors) {
        if (!(value instanceof String s)) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + property.name() + "' expects a binary id but got: " + describeValue(value)));
            return;
        }
        UUID binaryId;
        try {
            binaryId = UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + property.name() + "' is not a valid binary id: " + s));
            return;
        }
        if (binaryPartitionManager.getBinaryProperty(binaryId).isEmpty()) {
            errors.add(new ValidationError(path, PROPERTY_QUOTE_PREFIX + property.name() + "' references a binary that does not exist: " + s));
        }
    }

    private void validateScalar(String path, AdminPropertyDefinitionView property, Object value, List<ValidationError> errors) {
        boolean valid = switch (property.type()) {
            case STRING -> value instanceof String;
            case INT -> value instanceof Integer;
            case LONG -> value instanceof Integer || value instanceof Long;
            case DOUBLE -> value instanceof Double || value instanceof Integer || value instanceof Long;
            case BOOLEAN -> value instanceof Boolean;
            case DATE -> value instanceof String s && isValidDate(s);
            case DATETIME -> value instanceof String s && isValidDateTime(s);
            case BINARY -> false; // handled by the caller before reaching here
        };
        if (!valid) {
            errors.add(new ValidationError(path,
                    PROPERTY_QUOTE_PREFIX + property.name() + "' expects a " + property.type() + " value but got: " + describeValue(value)));
            return;
        }
        // Applies identically whether this call is validating a SINGLE property's own value or one
        // element of a LIST/SET property's value list -- both route through this one method.
        if (property.controlledListId() != null && !controlledListManager.containsValue(property.controlledListId(), property.type(), value)) {
            errors.add(new ValidationError(path,
                    PROPERTY_QUOTE_PREFIX + property.name() + "' value is not in its controlled list: " + describeValue(value)));
        }
    }

    private boolean isValidDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isValidDateTime(String s) {
        try {
            OffsetDateTime.parse(s);
            return true;
        } catch (Exception e) {
            try {
                Instant.parse(s);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private String describeValue(Object value) {
        return value.getClass().getSimpleName() + " (" + value + ")";
    }
}

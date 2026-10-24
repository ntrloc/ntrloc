package org.ntrloc.graph.mutation;

import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
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
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemLinkPerspectiveView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
// independently reachable from EntityManagerImpl, so it carries its own guard. The helpers that
// take a principal (processItemCreate here, PropertyValidator's write checks) are only called from
// process(), so they take a plain NtrlocPrincipal with no null check of their own.
@Component
public class MutationRequestProcessor {

    private static final String PROPERTIES_PATH_SUFFIX = ".properties";
    private static final String ITEM_TYPE_NAME_PATH_SUFFIX = ".itemTypeName";

    private final LedgerRegisterCoordinator coordinator;
    private final RegisterPartitionManager registerPartitionManager;
    private final SchemaManager schemaManager;
    private final PropertyValidator propertyValidator;
    private final LinkEndpointResolver linkEndpointResolver;
    private final PermissionService permissionService;

    public MutationRequestProcessor(LedgerRegisterCoordinator coordinator,
                                     RegisterPartitionManager registerPartitionManager,
                                     SchemaManager schemaManager,
                                     PropertyValidator propertyValidator,
                                     LinkEndpointResolver linkEndpointResolver,
                                     PermissionService permissionService) {
        this.coordinator = coordinator;
        this.registerPartitionManager = registerPartitionManager;
        this.schemaManager = schemaManager;
        this.propertyValidator = propertyValidator;
        this.linkEndpointResolver = linkEndpointResolver;
        this.permissionService = permissionService;
    }

    @Transactional
    @PreAuthorize("#principal != null")
    public MutationResponse process(MutationRequest request, NtrlocPrincipal principal) {
        RequestState state = new RequestState();

        List<ItemMutation> items = request.items() == null ? List.of() : request.items();
        List<LinkMutation> links = request.links() == null ? List.of() : request.links();

        // Items before links: link endpoint resolution needs new (refId) references already
        // assigned real ids and known types.
        for (int i = 0; i < items.size(); i++) {
            String path = "items[%d]".formatted(i);
            ItemMutation item = items.get(i);
            if (item instanceof ItemCreateMutation m) {
                processItemCreate(m, path, principal, state);
            } else if (item instanceof ItemUpdateMutation m) {
                processItemUpdate(m, path, principal, state);
            } else if (item instanceof ItemDeleteMutation m) {
                processItemDelete(m, path, principal, state);
            }
        }

        for (int i = 0; i < links.size(); i++) {
            String path = "links[%d]".formatted(i);
            LinkMutation link = links.get(i);
            if (link instanceof LinkCreateMutation m) {
                processLinkCreate(m, path, principal, state);
            } else if (link instanceof LinkUpdateMutation m) {
                processLinkUpdate(m, path, principal, state);
            } else if (link instanceof LinkDeleteMutation m) {
                processLinkDelete(m, path, principal, state);
            }
        }

        checkLinkPerspectiveCardinality(state);

        if (!state.errors.isEmpty()) {
            throw new MutationValidationException(state.errors);
        }

        UUID transactionId = UUID.randomUUID();
        UUID commitId = UUID.randomUUID();
        // principal is guaranteed non-null here (process()'s own @PreAuthorize guard) -- this is
        // also ledger attribution (who made this change), not just a permission-check artifact.
        // One OffsetDateTime generated here, not left to each write's own NOW(): this exact value
        // becomes both the ledger entries' created_date and the register rows' resulting
        // created_date/updated_date (see LedgerRegisterCoordinatorImpl.prepare).
        UUID actorUserId = principal.id();
        OffsetDateTime createdDate = OffsetDateTime.now(ZoneOffset.UTC);
        // prepare() now durably commits on its own (see LedgerRegisterCoordinatorImpl's
        // REQUIRES_NEW) regardless of which branch runs next -- commit=false intentionally stops
        // here, leaving a genuinely prepared, independently-recoverable transaction for a later
        // confirmCommit() call, not a half-finished one.
        coordinator.prepare(state.entries, transactionId, actorUserId, createdDate);
        if (request.commit() == null || request.commit()) {
            coordinator.commit(transactionId, commitId);
        }

        return new MutationResponse(state.itemResults, state.linkResults, transactionId);
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

    private void processItemCreate(ItemCreateMutation m, String path, NtrlocPrincipal principal, RequestState state) {
        List<ValidationError> errors = state.errors;
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
        state.newItems.put(itemId, new NewItemInfo(path, itemTypeId.get()));
        if (m.refId() != null) {
            state.refIdToItemId.put(m.refId(), itemId);
            state.refIdToItemTypeId.put(m.refId(), itemTypeId.get());
        }
        Map<UUID, Object> properties = propertyValidator.resolveItemPropertyIds(itemTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        // Create only, never update -- see PropertyValidator.checkRequiredItemProperties' comment on why an
        // update's partial-diff payload can't be checked the same way.
        propertyValidator.checkRequiredItemProperties(itemTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        propertyValidator.checkDeprecatedItemProperties(itemTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        state.entries.add(new ItemCreateEntry(itemId, itemTypeId.get(), properties, Map.of(), Set.of()));
        state.itemResults.add(new ItemMutationResult(m.refId(), itemId, MutationOperation.CREATE));
    }

    private void processItemUpdate(ItemUpdateMutation m, String path, NtrlocPrincipal principal, RequestState state) {
        List<ValidationError> errors = state.errors;
        Optional<UUID> itemTypeId = registerPartitionManager.findItemTypeId(m.itemId());
        if (itemTypeId.isEmpty()) {
            errors.add(new ValidationError(path + ".itemId", "Unknown item: " + m.itemId()));
            return;
        }
        // Property-write is marker-mediated, so it only ever applies here (an existing item, which
        // can already carry markers) -- never on create, where the item (and any markers on it)
        // doesn't exist yet. item-type:create alone governs what create-time properties are set.
        propertyValidator.checkCanWriteItemProperties(principal, itemTypeId.get(), m.itemId(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        Map<UUID, Object> properties = propertyValidator.resolveItemPropertyIds(itemTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        // The schema can require a property today that didn't exist, or wasn't REQUIRED, when this
        // item was created -- see PropertyValidator.checkRequiredPropertiesById's comment. Checked against the
        // item's own *resulting* state (current register row merged with this diff), not just the
        // diff in isolation, so a mutation that never mentions the property at all still has to
        // fail if the item would still be missing it afterward.
        propertyValidator.checkRequiredItemPropertiesOnUpdate(itemTypeId.get(), m.itemId(), properties, path + PROPERTIES_PATH_SUFFIX, errors);
        propertyValidator.checkDeprecatedItemProperties(itemTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        state.entries.add(new ItemUpdateEntry(m.itemId(), properties, Map.of(), Set.of(), Set.of(), Set.of()));
        state.itemResults.add(new ItemMutationResult(null, m.itemId(), MutationOperation.UPDATE));
    }

    private void processItemDelete(ItemDeleteMutation m, String path, NtrlocPrincipal principal, RequestState state) {
        if (!canDeleteItem(principal, m.itemId())) {
            state.errors.add(new ValidationError(path, "Not permitted to delete this item"));
            return;
        }
        state.entries.add(new ItemDeleteEntry(m.itemId()));
        state.itemResults.add(new ItemMutationResult(null, m.itemId(), MutationOperation.DELETE));
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

    private void processLinkCreate(LinkCreateMutation m, String path, NtrlocPrincipal principal, RequestState state) {
        List<ValidationError> errors = state.errors;
        Optional<LinkEndpointResolver.ResolvedLink> resolved =
                linkEndpointResolver.resolve(m, path, state.refIdToItemId, state.refIdToItemTypeId, errors);
        if (resolved.isEmpty()) return;

        UUID linkId = UUID.randomUUID();
        UUID linkTypeId = resolved.get().linkTypeId();
        LinkEndpoint endpointA = resolved.get().first();
        LinkEndpoint endpointB = resolved.get().second();
        // Both sides' own link:create grant are required, not just one -- a user with rights on
        // only one endpoint's perspective can't unilaterally attach a link into the other side.
        if (!canCreateLink(principal, endpointA) || !canCreateLink(principal, endpointB)) {
            errors.add(new ValidationError(path, "Not permitted to create this link"));
            return;
        }
        state.recordLinkDelta(endpointA.itemId(), endpointA.perspectiveId(), path, +1);
        state.recordLinkDelta(endpointB.itemId(), endpointB.perspectiveId(), path, +1);
        propertyValidator.checkCanWriteLinkProperties(principal, List.of(endpointA.itemId(), endpointB.itemId()), linkTypeId, m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        Map<UUID, Object> properties = propertyValidator.resolveLinkPropertyIds(linkTypeId, m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        propertyValidator.checkRequiredLinkProperties(linkTypeId, m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        propertyValidator.checkDeprecatedLinkProperties(linkTypeId, m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        state.entries.add(new LinkCreateEntry(linkId, linkTypeId, endpointA, endpointB, properties));
        state.linkResults.add(new LinkMutationResult(linkId, MutationOperation.CREATE));
    }

    private void processLinkUpdate(LinkUpdateMutation m, String path, NtrlocPrincipal principal, RequestState state) {
        List<ValidationError> errors = state.errors;
        Optional<UUID> linkTypeId = registerPartitionManager.findLinkTypeId(m.linkId());
        if (linkTypeId.isEmpty()) {
            errors.add(new ValidationError(path + ".linkId", "Unknown link: " + m.linkId()));
            return;
        }
        List<UUID> endpointItemIds = registerPartitionManager.findLinkEndpoints(m.linkId()).stream().map(RegisterLinkEndpoint::itemId).toList();
        propertyValidator.checkCanWriteLinkProperties(principal, endpointItemIds, linkTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        Map<UUID, Object> properties = propertyValidator.resolveLinkPropertyIds(linkTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        propertyValidator.checkRequiredLinkPropertiesOnUpdate(linkTypeId.get(), m.linkId(), properties, path + PROPERTIES_PATH_SUFFIX, errors);
        propertyValidator.checkDeprecatedLinkProperties(linkTypeId.get(), m.properties(), path + PROPERTIES_PATH_SUFFIX, errors);
        state.entries.add(new LinkUpdateEntry(m.linkId(), properties));
        state.linkResults.add(new LinkMutationResult(m.linkId(), MutationOperation.UPDATE));
    }

    // An update never changes endpoints (Section 8 -- see stageLinkUpdate's own comment), so unlike
    // create/delete there is no perspective-cardinality delta to record here.
    private void processLinkDelete(LinkDeleteMutation m, String path, NtrlocPrincipal principal, RequestState state) {
        List<RegisterLinkEndpoint> endpoints = registerPartitionManager.findLinkEndpoints(m.linkId());
        // Both sides' own link:delete grant are required, same symmetric reasoning as
        // processLinkCreate's own link:create check.
        if (!canDeleteLink(principal, endpoints)) {
            state.errors.add(new ValidationError(path, "Not permitted to delete this link"));
            return;
        }
        for (RegisterLinkEndpoint endpoint : endpoints) {
            state.recordLinkDelta(endpoint.itemId(), endpoint.perspectiveId(), path, -1);
        }
        state.entries.add(new LinkDeleteEntry(m.linkId()));
        state.linkResults.add(new LinkMutationResult(m.linkId(), MutationOperation.DELETE));
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

    // Everything process() accumulates while walking one request.
    //
    // Perspective cardinality can only be judged once the whole request has been walked: a new
    // item's minCardinality can only be met by link creates elsewhere in the same request. So
    // perspectiveDelta accumulates a net change per (item, perspective), and
    // checkLinkPerspectiveCardinality judges the totals at the end.
    private static final class RequestState {
        final List<ValidationError> errors = new ArrayList<>();
        final Map<String, UUID> refIdToItemId = new HashMap<>();
        final Map<String, UUID> refIdToItemTypeId = new HashMap<>();
        final List<LedgerEntry> entries = new ArrayList<>();
        final List<ItemMutationResult> itemResults = new ArrayList<>();
        final List<LinkMutationResult> linkResults = new ArrayList<>();
        final Map<UUID, NewItemInfo> newItems = new HashMap<>();
        final Map<LinkPerspectiveKey, Integer> perspectiveDelta = new HashMap<>();
        final Map<LinkPerspectiveKey, String> lastLinkMutationPath = new HashMap<>();

        void recordLinkDelta(UUID itemId, UUID perspectiveId, String path, int change) {
            LinkPerspectiveKey key = new LinkPerspectiveKey(itemId, perspectiveId);
            perspectiveDelta.merge(key, change, Integer::sum);
            lastLinkMutationPath.put(key, path);
        }
    }

    // Where checkLinkPerspectiveCardinality learns a new item's own type from, to enumerate its
    // perspectives in the second pass below -- see that method's own comment.
    private record NewItemInfo(String path, UUID itemTypeId) {}

    // Judged only once the whole request has been walked -- see RequestState's comment on why a
    // per-mutation check can't work for minCardinality. Two passes: the first judges every
    // perspective some link create/delete in this request actually touched; the second catches a
    // new item's own perspective that nothing touched at all (zero link creates for it, so it never
    // becomes a perspectiveDelta key), which would otherwise slip past the first pass entirely.
    private void checkLinkPerspectiveCardinality(RequestState state) {
        Map<LinkPerspectiveKey, Integer> perspectiveDelta = state.perspectiveDelta;
        Map<UUID, NewItemInfo> newItems = state.newItems;
        List<ValidationError> errors = state.errors;
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
            String path = newItem != null ? newItem.path() + ".links." + perspective.name() : state.lastLinkMutationPath.get(key);
            reportCardinalityViolation(perspective, existing + change, path, errors);
        });

        newItems.forEach((itemId, info) -> {
            for (PerspectiveLookup perspective : perspectivesFor(info.itemTypeId())) {
                boolean required = perspective.view().minCardinality() > 0;
                if (required && !perspectiveDelta.containsKey(new LinkPerspectiveKey(itemId, perspective.view().id()))) {
                    reportCardinalityViolation(perspective, 0, info.path() + ".links." + perspective.name(), errors);
                }
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
}

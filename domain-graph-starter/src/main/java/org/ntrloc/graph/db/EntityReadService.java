package org.ntrloc.graph.db;

import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.process.ProcessAccessible;
import org.ntrloc.graph.db.partition.register.RegisterStoreReader;
import org.ntrloc.graph.db.partition.schema.SchemaService;
import org.ntrloc.graph.principal.NtrlocPrincipal;
import org.ntrloc.graph.projection.CollectionProjectionSpec;
import org.ntrloc.graph.projection.ItemProjectionSpec;
import org.ntrloc.graph.projection.ProjectedItem;
import org.ntrloc.graph.projection.ProjectionEngine;
import org.ntrloc.graph.projection.ProjectionEngine.RenderOptions;
import org.ntrloc.graph.projection.ProjectionResult;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Entity Read (docs/ntrloc-architecture-rationalization.md §5): projections, always as someone, so always
// permission-filtered. Resolves the queried type (polymorphically) and the caller's access, then has
// ProjectionEngine project. Never depends on the write side.
//
// @ProcessAccessible: the one bean process scripts can reach, as ${entityReadService} -- for now; process
// collaborators get their own typed, per-process-type facades later (§6.5).
@Service
@ProcessAccessible
public class EntityReadService {

    private final RegisterStoreReader registerStore;
    private final ProjectionEngine projectionEngine;
    private final SchemaService schemaService;
    private final PermissionService permissionService;
    private final ProjectedUserInfoEnricher userInfoEnricher;

    public EntityReadService(RegisterStoreReader registerStore, ProjectionEngine projectionEngine, SchemaService schemaService,
                             PermissionService permissionService, ProjectedUserInfoEnricher userInfoEnricher) {
        this.registerStore = registerStore;
        this.projectionEngine = projectionEngine;
        this.schemaService = schemaService;
        this.permissionService = permissionService;
        this.userInfoEnricher = userInfoEnricher;
    }

    // Polymorphic by default: itemTypeName resolves to itself plus every descendant (see
    // SchemaService.resolveSupertypeInclusiveItemTypeIds), so a lookup by a supertype name still
    // finds an item that actually exists as a concrete subtype. The permission check runs against
    // the item's *actual* type, not the queried name -- what matters is whether this principal can
    // read the concrete data being returned, not the root of however it was searched for.
    @PreAuthorize("#principal != null")
    public Optional<ProjectedItem> project(ItemProjectionSpec spec, String binaryBaseUrl, NtrlocPrincipal principal) {
        UUID rootItemTypeId = resolveItemTypeId(spec.itemTypeName());
        Set<UUID> allowedItemTypeIds = schemaService.resolveSupertypeInclusiveItemTypeIds(rootItemTypeId);

        Optional<UUID> actualItemTypeId = registerStore.findItemTypeId(spec.itemId());
        if (actualItemTypeId.isEmpty() || !allowedItemTypeIds.contains(actualItemTypeId.get())) {
            return Optional.empty();
        }
        requireReadAccess(principal, actualItemTypeId.get(), spec.itemTypeName());
        // Instance-level check, separate from the type-level one above -- cheaper to check before
        // fetching the item's own (possibly large) properties than to fetch first and discard; see
        // ProjectionEngine.projectOne's own comment on why this lives here, not in a
        // query-level semi-join.
        if (!permissionService.canReadItem(principal, spec.itemId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown item type: " + spec.itemTypeName());
        }
        var permissionContext = permissionService.buildContext(principal);
        return userInfoEnricher.enrich(projectionEngine.projectOne(actualItemTypeId.get(), spec.itemId(), binaryBaseUrl,
                new RenderOptions(spec.links(), permissionContext, Boolean.TRUE.equals(spec.includePermissions()),
                        Boolean.TRUE.equals(spec.includeStates()), spec.properties())));
    }

    @PreAuthorize("#principal != null")
    public ProjectionResult project(CollectionProjectionSpec spec, String binaryBaseUrl, NtrlocPrincipal principal) {
        Set<UUID> itemTypeIds = resolveItemTypeIds(spec);

        // A *partial* drop (some branches unreadable, at least one isn't) proceeds silently --
        // a principal who can't read one specific subtype should still see every other branch of a
        // polymorphic query, not have the whole query fail. But if filtering leaves nothing
        // readable at all, that has to behave exactly like the pre-existing single-type case
        // already tested and relied on (AuthorizationEndpointsIntegrationTest): 404, not an empty
        // 200 -- an empty-but-200 response would leak "something exists here, you're just not
        // allowed to see it," which is exactly what the existing 404-not-403 principle exists to
        // avoid. requireReadAccess below reuses that exact same signal for the "nothing readable"
        // case, single-type or not.
        Set<UUID> readableItemTypeIds = itemTypeIds.stream()
                .filter(id -> permissionService.canReadItemType(principal, id))
                .collect(Collectors.toSet());
        if (readableItemTypeIds.isEmpty()) {
            String queriedName = spec.itemTypeName() != null ? spec.itemTypeName() : spec.traitName();
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown item type: " + queriedName);
        }

        var permissionContext = permissionService.buildContext(principal);
        // The common case (a type with no descendants, queried by name) goes through the existing,
        // proven single-table path unchanged -- projectAcrossTypes only engages once polymorphism
        // is actually in play, not on every request.
        return userInfoEnricher.enrich(readableItemTypeIds.size() == 1
                ? projectionEngine.project(readableItemTypeIds.iterator().next(), spec, binaryBaseUrl, permissionContext)
                : projectionEngine.projectAcrossTypes(readableItemTypeIds, spec, binaryBaseUrl, permissionContext));
    }

    // Exactly one of itemTypeName/traitName must be set; resolves to the set of concrete item-type
    // ids the query should span -- itself plus every descendant for a supertype root, or every
    // implementer (direct or inherited) for a trait.
    private Set<UUID> resolveItemTypeIds(CollectionProjectionSpec spec) {
        boolean hasItemType = spec.itemTypeName() != null && !spec.itemTypeName().isBlank();
        boolean hasTrait = spec.traitName() != null && !spec.traitName().isBlank();
        if (hasItemType == hasTrait) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Specify exactly one of itemTypeName or traitName");
        }
        if (hasItemType) {
            return schemaService.resolveSupertypeInclusiveItemTypeIds(resolveItemTypeId(spec.itemTypeName()));
        }
        return schemaService.resolveTraitImplementerItemTypeIds(resolveTraitId(spec.traitName()));
    }

    private UUID resolveTraitId(String traitName) {
        return schemaService.getAdminSchema().traits().stream()
                .filter(t -> t.name().equals(traitName))
                .map(t -> t.id())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown trait: " + traitName));
    }

    private void requireReadAccess(NtrlocPrincipal principal, UUID itemTypeId, String itemTypeName) {
        if (!permissionService.canReadItemType(principal, itemTypeId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown item type: " + itemTypeName);
        }
    }

    private UUID resolveItemTypeId(String itemTypeName) {
        return schemaService.getAdminSchema().items().stream()
                .filter(item -> item.name().equals(itemTypeName))
                .map(item -> item.id())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown item type: " + itemTypeName));
    }
}

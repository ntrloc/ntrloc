package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

@Service
public class PermissionService {

    public static final String ITEM_TYPE_READ = "item-type:read";
    public static final String ITEM_TYPE_CREATE = "item-type:create";

    // repo is only needed here now for getMarkerIdsForItem -- a data-sized register_item_marker
    // lookup that's deliberately never cached (see AuthorizationCacheManager's own comment). Every
    // grant/permission read below goes through the cache manager instead of issuing a live query.
    private final AuthorizationRepository repo;
    private final AuthorizationCacheManager cache;

    public PermissionService(AuthorizationRepository repo, AuthorizationCacheManager cache) {
        this.repo = repo;
        this.cache = cache;
    }

    /**
     * Single-item-type read check. Default deny: an item type with no direct item-type:read
     * grant is invisible to everyone — except superusers, who bypass authorization entirely.
     */
    public boolean canReadItemType(NtrlocPrincipal principal, UUID itemTypeId) {
        if (principal.isSuperuser()) {
            return true;
        }
        return cache.hasItemTypeGrant(principal.id(), principal.groupIds(), itemTypeId, ITEM_TYPE_READ);
    }

    /** Type-level create gate — a prerequisite for the (separate) hypothetical-instance create check. */
    public boolean canCreateItemType(NtrlocPrincipal principal, UUID itemTypeId) {
        if (principal.isSuperuser()) {
            return true;
        }
        return cache.hasItemTypeGrant(principal.id(), principal.groupIds(), itemTypeId, ITEM_TYPE_CREATE);
    }

    /** Every item type this principal can read, directly or via any group. Used to project the schema. */
    public Set<UUID> readableItemTypeIds(NtrlocPrincipal principal) {
        return cache.getGrantedItemTypeIds(principal.id(), principal.groupIds(), ITEM_TYPE_READ);
    }

    /**
     * Single-item instance read check (used by projectOne, where a query-level semi-join isn't in
     * play). Default deny: an item carrying no marker the principal holds item:read on is invisible.
     */
    public boolean canReadItem(NtrlocPrincipal principal, UUID itemId) {
        if (principal.isSuperuser()) {
            return true;
        }
        Set<UUID> granted = cache.getGrantedItemReadMarkerIds(principal.id(), principal.groupIds());
        if (granted.isEmpty()) {
            return false;
        }
        return !Collections.disjoint(repo.getMarkerIdsForItem(itemId), granted);
    }

    /**
     * Computed once per request, threaded through the whole projection response tree (see
     * RequestPermissionContext's own comment). Superuser short-circuits to empty sets — nothing
     * downstream should ever branch on those sets for a superuser context; callers must check
     * {@code superuser()} first, exactly like every other enforcement point in this class.
     */
    public RequestPermissionContext buildContext(NtrlocPrincipal principal) {
        if (principal.isSuperuser()) {
            return RequestPermissionContext.forSuperuser();
        }
        UUID userId = principal.id();
        Set<UUID> groupIds = principal.groupIds();
        return new RequestPermissionContext(
                false,
                readableItemTypeIds(principal),
                cache.getGrantedItemReadMarkerIds(userId, groupIds),
                cache.getGrantedItemDeleteMarkerIds(userId, groupIds),
                cache.getPropertyReadGrantsByMarker(userId, groupIds),
                cache.getPropertyWriteGrantsByMarker(userId, groupIds),
                cache.getLinkPropertyReadGrantsByMarker(userId, groupIds),
                cache.getLinkPropertyWriteGrantsByMarker(userId, groupIds),
                cache.getLinkPerspectiveReadGrantsByMarker(userId, groupIds),
                cache.getLinkPerspectiveDeleteGrantsByMarker(userId, groupIds));
    }
}

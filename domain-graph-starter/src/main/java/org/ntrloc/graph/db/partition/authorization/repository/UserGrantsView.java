package org.ntrloc.graph.db.partition.authorization.repository;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

// One user's effective grants -- their own and their groups' -- taken from a GrantsView (live's, or a
// draft's) once per request and asked every permission question that request has. The may* methods
// are the rules; the sets and maps are there too because projections push some of them into SQL (the
// item:read semi-join) or apply them in bulk to a page of items. See docs/ntrloc-acl-design-notes.md
// "Request-scoped permission context".
//
// Markers apply to items only, so each marker-scoped question takes the markers on the item concerned
// (for a link, its source item) as a lookup, made only when the answer depends on them: never for an
// admin. An admin's view allows everything and its sets are empty: check isAdmin() before reading them.
public record UserGrantsView(
        boolean isAdmin,
        Set<UUID> readableItemTypeIds,
        Set<UUID> creatableItemTypeIds,
        Set<UUID> grantedItemReadMarkerIds,
        Set<UUID> itemDeleteGrantedMarkerIds,
        Map<UUID, Set<UUID>> propertyReadGrantsByMarker,
        Map<UUID, Set<UUID>> propertyWriteGrantsByMarker,
        Map<UUID, Set<UUID>> linkPropertyReadGrantsByMarker,
        Map<UUID, Set<UUID>> linkPropertyWriteGrantsByMarker,
        Map<UUID, Set<UUID>> linkPerspectiveCreateGrantsByMarker,
        Map<UUID, Set<UUID>> linkPerspectiveReadGrantsByMarker,
        Map<UUID, Set<UUID>> linkPerspectiveDeleteGrantsByMarker,
        Map<UUID, Set<UUID>> transitionExecuteGrantsByMarker,
        Map<UUID, Set<UUID>> stateMachineStartGrantsByMarker
) {
    public static UserGrantsView forAdmin() {
        return new UserGrantsView(true, Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Default deny: an item type with no item-type:read grant is invisible. */
    public boolean mayReadItemType(UUID itemTypeId) {
        return isAdmin || readableItemTypeIds.contains(itemTypeId);
    }

    public boolean mayCreateItemType(UUID itemTypeId) {
        return isAdmin || creatableItemTypeIds.contains(itemTypeId);
    }

    /** Default deny: an item carrying no marker this user may read through is invisible. */
    public boolean mayReadItem(Supplier<Set<UUID>> itemMarkerIds) {
        return isAdmin || !Collections.disjoint(itemMarkerIds.get(), grantedItemReadMarkerIds);
    }

    public boolean mayDeleteItem(Supplier<Set<UUID>> itemMarkerIds) {
        return isAdmin || !Collections.disjoint(itemMarkerIds.get(), itemDeleteGrantedMarkerIds);
    }

    public boolean mayWriteProperty(Supplier<Set<UUID>> itemMarkerIds, UUID propertyId) {
        return isAdmin || anyMarkerGrants(propertyWriteGrantsByMarker, itemMarkerIds.get(), propertyId);
    }

    public boolean mayWriteLinkProperty(Supplier<Set<UUID>> sourceMarkerIds, UUID propertyId) {
        return isAdmin || anyMarkerGrants(linkPropertyWriteGrantsByMarker, sourceMarkerIds.get(), propertyId);
    }

    public boolean mayCreateLink(Supplier<Set<UUID>> sourceMarkerIds, UUID perspectiveId) {
        return isAdmin || anyMarkerGrants(linkPerspectiveCreateGrantsByMarker, sourceMarkerIds.get(), perspectiveId);
    }

    public boolean mayDeleteLink(Supplier<Set<UUID>> sourceMarkerIds, UUID perspectiveId) {
        return isAdmin || anyMarkerGrants(linkPerspectiveDeleteGrantsByMarker, sourceMarkerIds.get(), perspectiveId);
    }

    public boolean mayStartStateMachine(Supplier<Set<UUID>> itemMarkerIds, UUID stateMachineId) {
        return isAdmin || anyMarkerGrants(stateMachineStartGrantsByMarker, itemMarkerIds.get(), stateMachineId);
    }

    public boolean mayExecuteTransition(Supplier<Set<UUID>> itemMarkerIds, UUID transitionId) {
        return isAdmin || anyMarkerGrants(transitionExecuteGrantsByMarker, itemMarkerIds.get(), transitionId);
    }

    private static boolean anyMarkerGrants(Map<UUID, Set<UUID>> grantsByMarker, Set<UUID> itemMarkerIds, UUID targetId) {
        for (UUID markerId : itemMarkerIds) {
            if (grantsByMarker.getOrDefault(markerId, Set.of()).contains(targetId)) return true;
        }
        return false;
    }
}

package org.ntrloc.graph.db.partition.authorization;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Computed once per request (see PermissionService.buildContext), reused at every nesting depth
// of a projection response -- never recomputed per item, per link, or per recursion level. See
// docs/ntrloc-acl-design-notes.md "Request-scoped permission context".
//
// Deliberately a plain data record with no dependency back on PermissionService/AuthorizationRepository,
// so RegisterPartitionManager (which builds SQL and does the in-memory mode-2 resolution from
// this) doesn't need to depend on how these sets/maps are computed -- only on their shape.
//
// Mode 1 (existence-affecting, resolved as a SQL semi-join): grantedItemReadMarkerIds,
// grantedLinkReadMarkerIds. Mode 2 (field/capability-affecting, resolved in memory on an
// already-fetched page): everything else. See docs/ntrloc-acl-design-notes.md "Performance model".
public record RequestPermissionContext(
        boolean superuser,
        Set<UUID> readableItemTypeIds,
        Set<UUID> grantedItemReadMarkerIds,
        Set<UUID> grantedLinkReadMarkerIds,
        Set<UUID> itemDeleteGrantedMarkerIds,
        Set<UUID> linkDeleteGrantedMarkerIds,
        Map<UUID, Set<UUID>> propertyReadGrantsByMarker,
        Map<UUID, Set<UUID>> propertyWriteGrantsByMarker,
        Map<UUID, Set<UUID>> linkPropertyReadGrantsByMarker,
        Map<UUID, Set<UUID>> linkPropertyWriteGrantsByMarker
) {
    public static RequestPermissionContext forSuperuser() {
        return new RequestPermissionContext(true, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }
}

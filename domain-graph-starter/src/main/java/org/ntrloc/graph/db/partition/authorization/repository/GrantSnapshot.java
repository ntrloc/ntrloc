package org.ntrloc.graph.db.partition.authorization.repository;

import org.ntrloc.graph.db.partition.authorization.repository.AccessStoreReader.ItemTypeGrantRow;
import org.ntrloc.graph.db.partition.authorization.repository.AccessStoreReader.LinkPerspectiveGrantRow;
import org.ntrloc.graph.db.partition.authorization.repository.AccessStoreReader.MarkerGrantRow;
import org.ntrloc.graph.db.partition.authorization.repository.AccessStoreReader.MarkerScopedGrantRow;
import org.ntrloc.graph.db.partition.authorization.repository.AccessStoreReader.PropertyGrantRow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// AccessStore's in-memory copy of every grant definition -- schema-sized, admin-curated, rarely changing --
// never item markers (data-sized; those stay a real query). Immutable: AccessStore builds a new one after
// each grant write and swaps it in. See docs/ntrloc-acl-design-notes.md "Caching grant definitions, not
// item-marker assignments".
//
// Indexed by grantee (user or group), never by resolved principal: a principal's effective set is a
// lookup-time union over their own key and each of their groups' keys -- a few hashmap lookups, no I/O.
// So group-membership changes need no rebuild, and a grant change on one group never touches its members.
final class GrantSnapshot {

    private static final String PRINCIPAL_USER = "USER";
    private static final String PRINCIPAL_GROUP = "USER_GROUP";

    private record PrincipalKey(String principalType, UUID principalId) {}

    private record PrincipalOpKey(String principalType, UUID principalId, String operation) {}

    // Every grant row, as read from the store's tables.
    record Rows(List<ItemTypeGrantRow> itemTypeGrants, List<MarkerGrantRow> markerGrants,
                List<PropertyGrantRow> propertyGrants, List<PropertyGrantRow> linkPropertyGrants,
                List<LinkPerspectiveGrantRow> linkPerspectiveGrants, List<MarkerScopedGrantRow> transitionGrants,
                List<MarkerScopedGrantRow> stateMachineStartGrants) {}

    private final Map<PrincipalOpKey, Set<UUID>> itemTypeGrants = new HashMap<>();
    private final Map<PrincipalKey, Set<UUID>> itemReadMarkers = new HashMap<>();
    private final Map<PrincipalKey, Set<UUID>> itemDeleteMarkers = new HashMap<>();
    private final Map<PrincipalKey, Map<UUID, Set<UUID>>> propertyReadGrants = new HashMap<>();
    private final Map<PrincipalKey, Map<UUID, Set<UUID>>> propertyWriteGrants = new HashMap<>();
    private final Map<PrincipalKey, Map<UUID, Set<UUID>>> linkPropertyReadGrants = new HashMap<>();
    private final Map<PrincipalKey, Map<UUID, Set<UUID>>> linkPropertyWriteGrants = new HashMap<>();
    private final Map<PrincipalKey, Map<UUID, Set<UUID>>> linkPerspectiveCreateGrants = new HashMap<>();
    private final Map<PrincipalKey, Map<UUID, Set<UUID>>> linkPerspectiveReadGrants = new HashMap<>();
    private final Map<PrincipalKey, Map<UUID, Set<UUID>>> linkPerspectiveDeleteGrants = new HashMap<>();
    // Existence-only grants (transition:execute, state-machine:start): a row present == granted.
    private final Map<PrincipalKey, Map<UUID, Set<UUID>>> transitionExecuteGrants = new HashMap<>();
    private final Map<PrincipalKey, Map<UUID, Set<UUID>>> stateMachineStartGrants = new HashMap<>();

    GrantSnapshot(Rows rows) {
        for (var row : rows.itemTypeGrants()) {
            itemTypeGrants.computeIfAbsent(new PrincipalOpKey(row.principalType(), row.principalId(), row.permission()), k -> new HashSet<>())
                    .add(row.itemTypeId());
        }
        for (var row : rows.markerGrants()) {
            var key = new PrincipalKey(row.principalType(), row.principalId());
            if (row.itemCanRead()) itemReadMarkers.computeIfAbsent(key, k -> new HashSet<>()).add(row.markerId());
            if (row.itemCanDelete()) itemDeleteMarkers.computeIfAbsent(key, k -> new HashSet<>()).add(row.markerId());
        }
        indexPropertyGrants(rows.propertyGrants(), propertyReadGrants, propertyWriteGrants);
        indexPropertyGrants(rows.linkPropertyGrants(), linkPropertyReadGrants, linkPropertyWriteGrants);
        for (var row : rows.linkPerspectiveGrants()) {
            var key = new PrincipalKey(row.principalType(), row.principalId());
            if (row.canCreate()) addToNestedSet(linkPerspectiveCreateGrants, key, row.markerId(), row.perspectiveId());
            if (row.canRead()) addToNestedSet(linkPerspectiveReadGrants, key, row.markerId(), row.perspectiveId());
            if (row.canDelete()) addToNestedSet(linkPerspectiveDeleteGrants, key, row.markerId(), row.perspectiveId());
        }
        indexExistenceGrants(rows.transitionGrants(), transitionExecuteGrants);
        indexExistenceGrants(rows.stateMachineStartGrants(), stateMachineStartGrants);
    }

    private static void indexPropertyGrants(List<PropertyGrantRow> rows, Map<PrincipalKey, Map<UUID, Set<UUID>>> readGrants,
                                            Map<PrincipalKey, Map<UUID, Set<UUID>>> writeGrants) {
        for (var row : rows) {
            var key = new PrincipalKey(row.principalType(), row.principalId());
            if (row.canRead()) addToNestedSet(readGrants, key, row.markerId(), row.propertyId());
            if (row.canWrite()) addToNestedSet(writeGrants, key, row.markerId(), row.propertyId());
        }
    }

    private static void indexExistenceGrants(List<MarkerScopedGrantRow> rows, Map<PrincipalKey, Map<UUID, Set<UUID>>> grants) {
        for (var row : rows) {
            addToNestedSet(grants, new PrincipalKey(row.principalType(), row.principalId()), row.markerId(), row.targetId());
        }
    }

    private static void addToNestedSet(Map<PrincipalKey, Map<UUID, Set<UUID>>> index, PrincipalKey key, UUID markerId, UUID objectId) {
        index.computeIfAbsent(key, k -> new HashMap<>()).computeIfAbsent(markerId, k -> new HashSet<>()).add(objectId);
    }

    Set<UUID> grantedItemTypeIds(UUID userId, Set<UUID> groupIds, String permission) {
        Set<UUID> result = new HashSet<>(itemTypeGrants.getOrDefault(new PrincipalOpKey(PRINCIPAL_USER, userId, permission), Set.of()));
        for (UUID groupId : groupIds) {
            result.addAll(itemTypeGrants.getOrDefault(new PrincipalOpKey(PRINCIPAL_GROUP, groupId, permission), Set.of()));
        }
        return result;
    }

    Set<UUID> itemReadMarkerIds(UUID userId, Set<UUID> groupIds) {
        return effectiveSet(itemReadMarkers, userId, groupIds);
    }

    Set<UUID> itemDeleteMarkerIds(UUID userId, Set<UUID> groupIds) {
        return effectiveSet(itemDeleteMarkers, userId, groupIds);
    }

    Map<UUID, Set<UUID>> propertyReadGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return effectiveNestedSet(propertyReadGrants, userId, groupIds);
    }

    Map<UUID, Set<UUID>> propertyWriteGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return effectiveNestedSet(propertyWriteGrants, userId, groupIds);
    }

    Map<UUID, Set<UUID>> linkPropertyReadGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return effectiveNestedSet(linkPropertyReadGrants, userId, groupIds);
    }

    Map<UUID, Set<UUID>> linkPropertyWriteGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return effectiveNestedSet(linkPropertyWriteGrants, userId, groupIds);
    }

    Map<UUID, Set<UUID>> linkPerspectiveCreateGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return effectiveNestedSet(linkPerspectiveCreateGrants, userId, groupIds);
    }

    Map<UUID, Set<UUID>> linkPerspectiveReadGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return effectiveNestedSet(linkPerspectiveReadGrants, userId, groupIds);
    }

    Map<UUID, Set<UUID>> linkPerspectiveDeleteGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return effectiveNestedSet(linkPerspectiveDeleteGrants, userId, groupIds);
    }

    Map<UUID, Set<UUID>> transitionExecuteGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return effectiveNestedSet(transitionExecuteGrants, userId, groupIds);
    }

    Map<UUID, Set<UUID>> stateMachineStartGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return effectiveNestedSet(stateMachineStartGrants, userId, groupIds);
    }

    private static Set<UUID> effectiveSet(Map<PrincipalKey, Set<UUID>> index, UUID userId, Set<UUID> groupIds) {
        Set<UUID> result = new HashSet<>(index.getOrDefault(new PrincipalKey(PRINCIPAL_USER, userId), Set.of()));
        for (UUID groupId : groupIds) {
            result.addAll(index.getOrDefault(new PrincipalKey(PRINCIPAL_GROUP, groupId), Set.of()));
        }
        return result;
    }

    private static Map<UUID, Set<UUID>> effectiveNestedSet(Map<PrincipalKey, Map<UUID, Set<UUID>>> index, UUID userId, Set<UUID> groupIds) {
        Map<UUID, Set<UUID>> result = new HashMap<>();
        mergeInto(result, index.get(new PrincipalKey(PRINCIPAL_USER, userId)));
        for (UUID groupId : groupIds) {
            mergeInto(result, index.get(new PrincipalKey(PRINCIPAL_GROUP, groupId)));
        }
        return result;
    }

    private static void mergeInto(Map<UUID, Set<UUID>> target, Map<UUID, Set<UUID>> source) {
        if (source == null) return;
        source.forEach((markerId, objectIds) -> target.computeIfAbsent(markerId, k -> new HashSet<>()).addAll(objectIds));
    }
}

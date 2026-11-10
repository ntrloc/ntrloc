package org.ntrloc.graph.db.partition.authorization.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reading the Grant Store: grant lookups for a principal (its own grants plus its groups'), and the
 * admin listings. Anything may read it. See GrantStore.
 */
public interface GrantStoreReader {

    // The item-type grants' permissions, as stored.
    String ITEM_TYPE_READ = "item-type:read";
    String ITEM_TYPE_CREATE = "item-type:create";

    record GrantRow(UUID grantId, UUID itemTypeId, String itemTypeName, String permission) {}


    // Raw grant rows, one per grantee.
    record ItemTypeGrantRow(String principalType, UUID principalId, String permission, UUID itemTypeId) {}

    record MarkerGrantRow(String principalType, UUID principalId, UUID markerId, boolean itemCanRead, boolean itemCanDelete) {}

    record PropertyGrantRow(String principalType, UUID principalId, UUID markerId, UUID propertyId, boolean canRead, boolean canWrite) {}

    record LinkPerspectiveGrantRow(String principalType, UUID principalId, UUID markerId, UUID perspectiveId, boolean canCreate, boolean canRead, boolean canDelete) {}

    // The two existence-only grant kinds (transition:execute, state-machine:start): targetId is a
    // transition id or a state-machine id respectively.
    record MarkerScopedGrantRow(String principalType, UUID principalId, UUID markerId, UUID targetId) {}

    record PropertyAccessRow(UUID propertyId, boolean canRead, boolean canWrite) {}

    record LinkPerspectiveAccessRow(UUID perspectiveId, boolean canCreate, boolean canRead, boolean canDelete) {}

    // The marker grant's own item-level verbs (item_can_read / item_can_delete live directly on
    // marker_grant, not a child table). No grant row at all == both false.
    record ItemLevelAccessRow(boolean canRead, boolean canDelete) {}

    // --- Grant lookups: a user's own grants unioned with each of their groups' ---

    boolean hasItemTypeGrant(UUID userId, Set<UUID> groupIds, UUID itemTypeId, String permission);

    Set<UUID> getGrantedItemTypeIds(UUID userId, Set<UUID> groupIds, String permission);

    Set<UUID> getGrantedItemReadMarkerIds(UUID userId, Set<UUID> groupIds);

    Set<UUID> getGrantedItemDeleteMarkerIds(UUID userId, Set<UUID> groupIds);

    /** markerId -> property ids this marker grants read for. */
    Map<UUID, Set<UUID>> getPropertyReadGrantsByMarker(UUID userId, Set<UUID> groupIds);

    Map<UUID, Set<UUID>> getPropertyWriteGrantsByMarker(UUID userId, Set<UUID> groupIds);

    Map<UUID, Set<UUID>> getLinkPropertyReadGrantsByMarker(UUID userId, Set<UUID> groupIds);

    Map<UUID, Set<UUID>> getLinkPropertyWriteGrantsByMarker(UUID userId, Set<UUID> groupIds);

    /** markerId -> perspective ids this marker grants link:create for. */
    Map<UUID, Set<UUID>> getLinkPerspectiveCreateGrantsByMarker(UUID userId, Set<UUID> groupIds);

    /** markerId -> perspective ids this marker grants link:read/traversal for. */
    Map<UUID, Set<UUID>> getLinkPerspectiveReadGrantsByMarker(UUID userId, Set<UUID> groupIds);

    /** markerId -> perspective ids this marker grants link:delete for. */
    Map<UUID, Set<UUID>> getLinkPerspectiveDeleteGrantsByMarker(UUID userId, Set<UUID> groupIds);

    /** markerId -> transition ids this marker grants transition:execute for. */
    Map<UUID, Set<UUID>> getTransitionExecuteGrantsByMarker(UUID userId, Set<UUID> groupIds);

    /** markerId -> state-machine ids this marker grants state-machine:start for. */
    Map<UUID, Set<UUID>> getStateMachineStartGrantsByMarker(UUID userId, Set<UUID> groupIds);

    /** A user's or group's own permissions -- not their groups'. */
    Set<Permission> getPermissions(String principalType, UUID principalId);

    /** Every live grant, as the lookups above answer them. Replaced whole on change. */
    GrantsView getGrantsView();

    // --- Admin listings, per grantee or marker ---

    Optional<UUID> findItemTypeGrant(UUID itemTypeId, String principalType, UUID principalId, String permission);

    List<GrantRow> getItemTypeGrantsForPrincipal(String principalType, UUID principalId);

    List<GrantRow> getItemTypeGrantsForUserGroups(Set<UUID> groupIds);

    List<ItemTypeGrantRow> getAllItemTypeGrants();

    List<MarkerGrantRow> getAllMarkerGrants();

    Optional<UUID> findMarkerGrant(UUID markerId, String principalType, UUID principalId);

    ItemLevelAccessRow getItemPermissionsForMarker(UUID markerId, String principalType, UUID principalId);

    List<PropertyAccessRow> getPropertyGrantsForMarker(UUID markerId, String principalType, UUID principalId);

    List<PropertyAccessRow> getLinkPropertyGrantsForMarker(UUID markerId, String principalType, UUID principalId);

    List<LinkPerspectiveAccessRow> getLinkPerspectiveGrantsForMarker(UUID markerId, String principalType, UUID principalId);

    Set<UUID> getTransitionGrantsForMarker(UUID markerId, String principalType, UUID principalId);

    Set<UUID> getStateMachineStartGrantsForMarker(UUID markerId, String principalType, UUID principalId);

}

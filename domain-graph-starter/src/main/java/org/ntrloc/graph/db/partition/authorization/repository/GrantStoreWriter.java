package org.ntrloc.graph.db.partition.authorization.repository;


import java.util.Collection;
import java.util.UUID;

/** Writing the Grant Store: item-type grants and marker grants. See GrantStore. */
public interface GrantStoreWriter {
    /** One permission on or off, keeping the principal's other permissions as they are. */
    void setPermission(String principalType, UUID principalId, Permission permission, boolean value);

    void grantItemType(UUID itemTypeId, String principalType, UUID principalId, String permission);

    void grantItemTypeIfAbsent(UUID itemTypeId, String principalType, UUID principalId, String permission);

    void deleteItemTypeGrant(UUID grantId);

    UUID ensureMarkerGrant(UUID markerId, String principalType, UUID principalId);

    void setItemPermissions(UUID markerGrantId, boolean canRead, boolean canDelete);

    void grantPropertyAccess(UUID markerGrantId, UUID propertyId, boolean canRead, boolean canWrite);

    void grantLinkPropertyAccess(UUID markerGrantId, UUID propertyId, boolean canRead, boolean canWrite);

    void grantLinkPerspectiveAccess(UUID markerGrantId, UUID perspectiveId, boolean canCreate, boolean canRead, boolean canDelete);

    void grantTransitionExecute(UUID markerGrantId, UUID transitionId);

    void revokeTransitionExecute(UUID markerGrantId, UUID transitionId);

    void grantStateMachineStart(UUID markerGrantId, UUID stateMachineId);

    void revokeStateMachineStart(UUID markerGrantId, UUID stateMachineId);

    void deleteMarkerGrant(UUID markerGrantId);

    /** Every grant made against this marker -- before the marker itself is deleted. */
    void deleteGrantsForMarker(UUID markerId);

    /** Reloads this node's view: grant rows went by a database cascade (a schema element deleted). */
    void reloadAfterCascade();

    /** Every grant to these users or groups (principalType USER or USER_GROUP) -- as they're deleted. */
    void deleteGrantsForPrincipals(String principalType, Collection<UUID> principalIds);
}

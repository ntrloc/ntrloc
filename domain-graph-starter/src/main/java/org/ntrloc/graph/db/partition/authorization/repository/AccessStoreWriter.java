package org.ntrloc.graph.db.partition.authorization.repository;

import org.ntrloc.graph.db.partition.authorization.repository.AccessStoreReader.MarkerRow;

import java.util.Optional;
import java.util.UUID;

/** Writing the Access Store: item-type grants, markers, and marker grants. See AccessStore. */
public interface AccessStoreWriter {

    void grantItemType(UUID itemTypeId, String principalType, UUID principalId, String permission);

    void grantItemTypeIfAbsent(UUID itemTypeId, String principalType, UUID principalId, String permission);

    void deleteItemTypeGrant(UUID grantId);

    MarkerRow createMarker(String name, String description, String scopeKind, UUID scopeId);

    Optional<MarkerRow> updateMarker(UUID id, String name, String description);

    void deleteMarker(UUID id);

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
}

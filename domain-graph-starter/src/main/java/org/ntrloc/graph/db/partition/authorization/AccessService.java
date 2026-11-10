package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreWriter;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerStoreReader.MarkerRow;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerStoreWriter;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.authorization.repository.UserGrantsView;
import org.ntrloc.graph.principal.NtrlocPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

// Grants and markers (docs/ntrloc-drafts-plan.md step 1.2): their only writer, and where a user's
// grants are read for permission checks. Markers exist to scope grants, so one service owns both
// stores: a marker's grants go before the marker, in one transaction. Each write is one transaction;
// the Grant Store rebuilds its view once that commits.
@Service
public class AccessService {

    private final GrantStoreReader grantReader;
    private final GrantStoreWriter grantWriter;
    private final MarkerStoreWriter markerWriter;

    public AccessService(GrantStoreReader grantReader, GrantStoreWriter grantWriter, MarkerStoreWriter markerWriter) {
        this.grantReader = grantReader;
        this.grantWriter = grantWriter;
        this.markerWriter = markerWriter;
    }

    /**
     * This user's live grants, for every permission question in a request. Admins get a view that
     * allows everything. Demands a real principal, as every read and write service does
     * (docs/ntrloc-principal-enforcement-plan.md, Part C).
     */
    @PreAuthorize("#user != null")
    public UserGrantsView getUserGrants(NtrlocPrincipal user) {
        return user.isAdmin() ? UserGrantsView.forAdmin() : grantReader.getGrantsView().forUser(user.id(), user.groupIds());
    }

    // --- One permission at a time (the grants API) ---

    /**
     * Grants or removes one permission for a user ({@code USER}) or group ({@code USER_GROUP}), leaving
     * the principal's other permissions as they are. Setting what's already set changes nothing.
     */
    @Transactional
    public void setPermission(String principalType, UUID principalId, Permission permission, boolean value) {
        grantWriter.setPermission(principalType, principalId, permission, value);
    }

    // --- Item-type grants ---

    @Transactional
    public void grantItemType(UUID itemTypeId, String principalType, UUID principalId, String permission) {
        grantWriter.grantItemTypeIfAbsent(itemTypeId, principalType, principalId, permission);
    }

    /** False when there was no such grant. */
    @Transactional
    public boolean revokeItemType(UUID itemTypeId, String principalType, UUID principalId, String permission) {
        Optional<UUID> grantId = grantReader.findItemTypeGrant(itemTypeId, principalType, principalId, permission);
        grantId.ifPresent(grantWriter::deleteItemTypeGrant);
        return grantId.isPresent();
    }

    // --- Marker grants: each creates the principal's grant of the marker if it has none yet ---

    @Transactional
    public void setItemPermissions(UUID markerId, String principalType, UUID principalId, boolean canRead, boolean canDelete) {
        grantWriter.setItemPermissions(grantWriter.ensureMarkerGrant(markerId, principalType, principalId), canRead, canDelete);
    }

    @Transactional
    public void setPropertyAccess(UUID markerId, String principalType, UUID principalId, UUID propertyId, boolean canRead, boolean canWrite) {
        grantWriter.grantPropertyAccess(grantWriter.ensureMarkerGrant(markerId, principalType, principalId), propertyId, canRead, canWrite);
    }

    @Transactional
    public void setLinkPropertyAccess(UUID markerId, String principalType, UUID principalId, UUID propertyId, boolean canRead, boolean canWrite) {
        grantWriter.grantLinkPropertyAccess(grantWriter.ensureMarkerGrant(markerId, principalType, principalId), propertyId, canRead, canWrite);
    }

    @Transactional
    public void setLinkPerspectiveAccess(UUID markerId, String principalType, UUID principalId, UUID perspectiveId,
                                         boolean canCreate, boolean canRead, boolean canDelete) {
        grantWriter.grantLinkPerspectiveAccess(grantWriter.ensureMarkerGrant(markerId, principalType, principalId),
                perspectiveId, canCreate, canRead, canDelete);
    }

    @Transactional
    public void grantTransition(UUID markerId, String principalType, UUID principalId, UUID transitionId) {
        grantWriter.grantTransitionExecute(grantWriter.ensureMarkerGrant(markerId, principalType, principalId), transitionId);
    }

    @Transactional
    public void revokeTransition(UUID markerId, String principalType, UUID principalId, UUID transitionId) {
        grantReader.findMarkerGrant(markerId, principalType, principalId)
                .ifPresent(markerGrantId -> grantWriter.revokeTransitionExecute(markerGrantId, transitionId));
    }

    @Transactional
    public void grantStateMachineStart(UUID markerId, String principalType, UUID principalId, UUID stateMachineId) {
        grantWriter.grantStateMachineStart(grantWriter.ensureMarkerGrant(markerId, principalType, principalId), stateMachineId);
    }

    @Transactional
    public void revokeStateMachineStart(UUID markerId, String principalType, UUID principalId, UUID stateMachineId) {
        grantReader.findMarkerGrant(markerId, principalType, principalId)
                .ifPresent(markerGrantId -> grantWriter.revokeStateMachineStart(markerGrantId, stateMachineId));
    }

    /** Everything this principal is granted through this marker. */
    @Transactional
    public void deleteMarkerGrant(UUID markerId, String principalType, UUID principalId) {
        grantReader.findMarkerGrant(markerId, principalType, principalId).ifPresent(grantWriter::deleteMarkerGrant);
    }

    // --- Markers ---

    @Transactional
    public MarkerRow createMarker(String name, String description, String scopeKind, UUID scopeId) {
        return markerWriter.createMarker(name, description, scopeKind, scopeId);
    }

    @Transactional
    public Optional<MarkerRow> updateMarker(UUID id, String name, String description) {
        return markerWriter.updateMarker(id, name, description);
    }

    @Transactional
    public void deleteMarker(UUID id) {
        grantWriter.deleteGrantsForMarker(id);
        markerWriter.deleteMarker(id);
    }
}

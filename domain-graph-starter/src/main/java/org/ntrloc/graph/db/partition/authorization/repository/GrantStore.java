package org.ntrloc.graph.db.partition.authorization.repository;

import com.hazelcast.topic.ITopic;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.partition.changelog.ChangeLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

// The Grant Store: item-type grants, and marker grants with their child tables (docs/ntrloc-drafts-plan.md
// step 1.1). Reads through GrantStoreReader, writes through GrantStoreWriter.
@Component
public class GrantStore implements GrantStoreReader, GrantStoreWriter {

    private static final String COL_ITEM_TYPE_ID = "item_type_id";
    private static final String COL_PERMISSION = "permission";
    private static final String COL_MARKER_ID = "marker_id";
    private static final String COL_PRINCIPAL_TYPE = "principal_type";
    private static final String COL_PRINCIPAL_ID = "principal_id";
    private static final String COL_PROPERTY_ID = "property_id";
    private static final String COL_PERSPECTIVE_ID = "perspective_id";
    private static final String COL_CAN_READ = "can_read";
    private static final String COL_CAN_WRITE = "can_write";
    private static final String PARAM_ITEM_TYPE_ID = "itemTypeId";
    private static final String PARAM_PRINCIPAL_TYPE = "principalType";
    private static final String PARAM_PRINCIPAL_ID = "principalId";
    private static final String PARAM_PERMISSION = "permission";
    private static final String PARAM_MARKER_ID = "markerId";
    private static final String PARAM_MARKER_GRANT_ID = "markerGrantId";
    private static final String PARAM_PROPERTY_ID = "propertyId";
    private static final String PARAM_PERSPECTIVE_ID = "perspectiveId";
    private static final String PARAM_CAN_READ = "canRead";
    private static final String PARAM_CAN_WRITE = "canWrite";

    private static final String GRANTS_CHANGED_TOPIC = "authorizationChanged";

    private final JdbcClient jdbcClient;
    private final ClusterService clusterService;
    private final ChangeLog changeLog;
    private final ITopic<String> grantsChangedTopic;
    // AtomicReference: a rebuild can run on the Hazelcast listener thread (another node's change) as
    // well as on the writing thread -- same as SchemaService's cachedAdminSchema.
    private final AtomicReference<GrantsView> grants = new AtomicReference<>();

    // Grant lookups are answered from an in-memory GrantsView of every grant definition, rebuilt after
    // each grant write (so a write then read is always consistent) and on every other node through the
    // cluster topic -- the same pattern as SchemaService. Callers can't tell; that's the point.
    public GrantStore(JdbcClient jdbcClient, ClusterService clusterService, ChangeLog changeLog) {
        this.jdbcClient = jdbcClient;
        this.clusterService = clusterService;
        this.changeLog = changeLog;
        rebuildGrants();
        this.grantsChangedTopic = clusterService.getTopic(GRANTS_CHANGED_TOPIC);
        grantsChangedTopic.addMessageListener(message -> {
            if (!message.getPublishingMember().equals(clusterService.getLocalMember())) {
                rebuildGrants();
            }
        });
    }

    private void rebuildGrants() {
        grants.set(new GrantsView(grantRows(null)));
    }

    // After every grant write: rebuild this node's view, then tell the other nodes. Inside a transaction
    // that waits for the commit (once per transaction), so neither sees uncommitted or rolled-back rows.
    private void grantsChanged() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishGrantsChanged();
            return;
        }
        if (TransactionSynchronizationManager.hasResource(this)) {
            return;
        }
        TransactionSynchronizationManager.bindResource(this, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResource(GrantStore.this);
                if (status == STATUS_COMMITTED) {
                    publishGrantsChanged();
                }
            }
        });
    }

    private void publishGrantsChanged() {
        rebuildGrants();
        grantsChangedTopic.publish(UUID.randomUUID().toString());
    }

    // --- Grant lookups (from the snapshot) ---

    @Override
    public boolean hasItemTypeGrant(UUID userId, Set<UUID> groupIds, UUID itemTypeId, String permission) {
        return grants.get().grantedItemTypeIds(userId, groupIds, permission).contains(itemTypeId);
    }

    @Override
    public Set<UUID> getGrantedItemTypeIds(UUID userId, Set<UUID> groupIds, String permission) {
        return grants.get().grantedItemTypeIds(userId, groupIds, permission);
    }

    @Override
    public Set<UUID> getGrantedItemReadMarkerIds(UUID userId, Set<UUID> groupIds) {
        return grants.get().itemReadMarkerIds(userId, groupIds);
    }

    @Override
    public Set<UUID> getGrantedItemDeleteMarkerIds(UUID userId, Set<UUID> groupIds) {
        return grants.get().itemDeleteMarkerIds(userId, groupIds);
    }

    @Override
    public Map<UUID, Set<UUID>> getPropertyReadGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return grants.get().propertyReadGrantsByMarker(userId, groupIds);
    }

    @Override
    public Map<UUID, Set<UUID>> getPropertyWriteGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return grants.get().propertyWriteGrantsByMarker(userId, groupIds);
    }

    @Override
    public Map<UUID, Set<UUID>> getLinkPropertyReadGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return grants.get().linkPropertyReadGrantsByMarker(userId, groupIds);
    }

    @Override
    public Map<UUID, Set<UUID>> getLinkPropertyWriteGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return grants.get().linkPropertyWriteGrantsByMarker(userId, groupIds);
    }

    @Override
    public Map<UUID, Set<UUID>> getLinkPerspectiveCreateGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return grants.get().linkPerspectiveCreateGrantsByMarker(userId, groupIds);
    }

    @Override
    public Map<UUID, Set<UUID>> getLinkPerspectiveReadGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return grants.get().linkPerspectiveReadGrantsByMarker(userId, groupIds);
    }

    @Override
    public Map<UUID, Set<UUID>> getLinkPerspectiveDeleteGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return grants.get().linkPerspectiveDeleteGrantsByMarker(userId, groupIds);
    }

    @Override
    public Map<UUID, Set<UUID>> getTransitionExecuteGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return grants.get().transitionExecuteGrantsByMarker(userId, groupIds);
    }

    @Override
    public Map<UUID, Set<UUID>> getStateMachineStartGrantsByMarker(UUID userId, Set<UUID> groupIds) {
        return grants.get().stateMachineStartGrantsByMarker(userId, groupIds);
    }

    // --- Direct item-type grant writes (each rebuilds the grant snapshot) ---

    @Override
    public void grantItemType(UUID itemTypeId, String principalType, UUID principalId, String permission) {
        logged(principalType, principalId, () -> {
            jdbcClient.sql("""
                    INSERT INTO authorization_item_type_grant (item_type_id, principal_type, principal_id, permission)
                    VALUES (:itemTypeId, :principalType, :principalId, :permission)
                    """)
                    .param(PARAM_ITEM_TYPE_ID, itemTypeId).param(PARAM_PRINCIPAL_TYPE, principalType)
                    .param(PARAM_PRINCIPAL_ID, principalId).param(PARAM_PERMISSION, permission)
                    .update();
            grantsChanged();
        });
    }

    @Override
    public void grantItemTypeIfAbsent(UUID itemTypeId, String principalType, UUID principalId, String permission) {
        logged(principalType, principalId, () -> {
            jdbcClient.sql("""
                    INSERT INTO authorization_item_type_grant (id, item_type_id, principal_type, principal_id, permission)
                    VALUES (gen_random_uuid(), :itemTypeId, :principalType, :principalId, :permission)
                    ON CONFLICT DO NOTHING
                    """)
                    .param(PARAM_ITEM_TYPE_ID, itemTypeId).param(PARAM_PRINCIPAL_TYPE, principalType)
                    .param(PARAM_PRINCIPAL_ID, principalId).param(PARAM_PERMISSION, permission)
                    .update();
            grantsChanged();
        });
    }

    @Override
    public Optional<UUID> findItemTypeGrant(UUID itemTypeId, String principalType, UUID principalId, String permission) {
        return jdbcClient.sql("""
                SELECT id FROM authorization_item_type_grant
                WHERE item_type_id = :itemTypeId AND principal_type = :principalType
                  AND principal_id = :principalId AND permission = :permission
                """)
                .param(PARAM_ITEM_TYPE_ID, itemTypeId).param(PARAM_PRINCIPAL_TYPE, principalType)
                .param(PARAM_PRINCIPAL_ID, principalId).param(PARAM_PERMISSION, permission)
                .query((rs, _) -> rs.getObject("id", UUID.class))
                .optional();
    }

    @Override
    public void deleteItemTypeGrant(UUID grantId) {
        loggedForItemTypeGrant(grantId, () -> {
            jdbcClient.sql("DELETE FROM authorization_item_type_grant WHERE id = :id")
                    .param("id", grantId).update();
            grantsChanged();
        });
    }

    @Override
    public Set<Permission> getPermissions(String principalType, UUID principalId) {
        return grants.get().permissionsOf(principalType, principalId);
    }

    @Override
    public GrantsView getGrantsView() {
        return grants.get();
    }

    // One permission is one op on one row; the row's other ops are read first and kept.
    @Override
    public void setPermission(String principalType, UUID principalId, Permission permission, boolean value) {
        logged(principalType, principalId, () -> {
            if (Permission.ITEM_TYPE.equals(permission.kind())) {
                setItemTypePermission(principalType, principalId, permission, value);
                return;
            }
            UUID markerId = permission.markerId();
            UUID targetId = permission.targetId();
            String op = permission.op();
            switch (permission.area()) {
                case Permission.AREA_ITEM -> {
                    var current = getItemPermissionsForMarker(markerId, principalType, principalId);
                    setItemPermissions(ensureMarkerGrant(markerId, principalType, principalId),
                            opOr(op, "read", value, current.canRead()), opOr(op, "delete", value, current.canDelete()));
                }
                case Permission.AREA_PROPERTY -> {
                    var current = propertyAccess(getPropertyGrantsForMarker(markerId, principalType, principalId), targetId);
                    grantPropertyAccess(ensureMarkerGrant(markerId, principalType, principalId), targetId,
                            opOr(op, "read", value, current.canRead()), opOr(op, "write", value, current.canWrite()));
                }
                case Permission.AREA_LINK_PROPERTY -> {
                    var current = propertyAccess(getLinkPropertyGrantsForMarker(markerId, principalType, principalId), targetId);
                    grantLinkPropertyAccess(ensureMarkerGrant(markerId, principalType, principalId), targetId,
                            opOr(op, "read", value, current.canRead()), opOr(op, "write", value, current.canWrite()));
                }
                case Permission.AREA_PERSPECTIVE -> setPerspectivePermission(principalType, principalId, markerId, targetId, op, value);
                case Permission.AREA_TRANSITION -> {
                    if (value) grantTransitionExecute(ensureMarkerGrant(markerId, principalType, principalId), targetId);
                    else findMarkerGrant(markerId, principalType, principalId).ifPresent(id -> revokeTransitionExecute(id, targetId));
                }
                case Permission.AREA_START -> {
                    if (value) grantStateMachineStart(ensureMarkerGrant(markerId, principalType, principalId), targetId);
                    else findMarkerGrant(markerId, principalType, principalId).ifPresent(id -> revokeStateMachineStart(id, targetId));
                }
                default -> throw new IllegalArgumentException("unknown area " + permission.area());
            }
        });
    }

    private void setItemTypePermission(String principalType, UUID principalId, Permission permission, boolean value) {
        String grant = "item-type:" + permission.op();
        if (value) {
            grantItemTypeIfAbsent(permission.itemTypeId(), principalType, principalId, grant);
        } else {
            findItemTypeGrant(permission.itemTypeId(), principalType, principalId, grant).ifPresent(this::deleteItemTypeGrant);
        }
    }

    private void setPerspectivePermission(String principalType, UUID principalId, UUID markerId, UUID perspectiveId, String op, boolean value) {
        var current = getLinkPerspectiveGrantsForMarker(markerId, principalType, principalId).stream()
                .filter(r -> r.perspectiveId().equals(perspectiveId)).findFirst()
                .orElse(new LinkPerspectiveAccessRow(perspectiveId, false, false, false));
        grantLinkPerspectiveAccess(ensureMarkerGrant(markerId, principalType, principalId), perspectiveId,
                opOr(op, "create", value, current.canCreate()), opOr(op, "read", value, current.canRead()),
                opOr(op, "delete", value, current.canDelete()));
    }

    // The new value for the op being set; the row's current value for each of its other ops.
    private static boolean opOr(String op, String rowOp, boolean value, boolean current) {
        return rowOp.equals(op) ? value : current;
    }

    private static PropertyAccessRow propertyAccess(List<PropertyAccessRow> rows, UUID propertyId) {
        return rows.stream().filter(r -> r.propertyId().equals(propertyId)).findFirst()
                .orElse(new PropertyAccessRow(propertyId, false, false));
    }

    // --- Admin permission reads (low-traffic admin-panel listings; not cached) ---

    @Override
    public List<GrantRow> getItemTypeGrantsForPrincipal(String principalType, UUID principalId) {
        return jdbcClient.sql("""
                SELECT g.id AS grant_id, g.item_type_id, si.name AS item_type_name, g.permission
                FROM authorization_item_type_grant g
                JOIN schema_item si ON si.id = g.item_type_id
                WHERE g.principal_type = :principalType AND g.principal_id = :principalId
                ORDER BY si.name, g.permission
                """)
                .param(PARAM_PRINCIPAL_TYPE, principalType)
                .param(PARAM_PRINCIPAL_ID, principalId)
                .query((rs, _) -> new GrantRow(
                        rs.getObject("grant_id", UUID.class),
                        rs.getObject(COL_ITEM_TYPE_ID, UUID.class),
                        rs.getString("item_type_name"),
                        rs.getString(COL_PERMISSION)))
                .list();
    }

    @Override
    public List<GrantRow> getItemTypeGrantsForUserGroups(Set<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT g.id AS grant_id, g.item_type_id, si.name AS item_type_name, g.permission
                FROM authorization_item_type_grant g
                JOIN schema_item si ON si.id = g.item_type_id
                WHERE g.principal_type = 'USER_GROUP' AND g.principal_id IN (:groupIds)
                ORDER BY si.name, g.permission
                """)
                .param("groupIds", groupIds)
                .query((rs, _) -> new GrantRow(
                        rs.getObject("grant_id", UUID.class),
                        rs.getObject(COL_ITEM_TYPE_ID, UUID.class),
                        rs.getString("item_type_name"),
                        rs.getString(COL_PERMISSION)))
                .list();
    }


    // --- Marker grant writes (each rebuilds the grant snapshot) ---
    // One marker_grant row per (marker, principal) pair -- item_can_read/item_can_delete live
    // directly on it (strictly 1:1: the only "item" a grant's item-level verbs ever reference is
    // whatever item carries the marker). Everything else (properties, link perspectives, link
    // properties, transitions) is genuinely one-to-many per grant and gets its own child table.

    @Override
    public UUID ensureMarkerGrant(UUID markerId, String principalType, UUID principalId) {
        UUID existing = jdbcClient.sql("""
                SELECT id FROM marker_grant WHERE marker_id = :markerId AND principal_type = :principalType AND principal_id = :principalId
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType).param(PARAM_PRINCIPAL_ID, principalId)
                .query(UUID.class).optional().orElse(null);
        if (existing != null) return existing;
        return jdbcClient.sql("""
                INSERT INTO marker_grant (marker_id, principal_type, principal_id) VALUES (:markerId, :principalType, :principalId)
                RETURNING id
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType).param(PARAM_PRINCIPAL_ID, principalId)
                .query(UUID.class).single();
    }

    @Override
    public void setItemPermissions(UUID markerGrantId, boolean canRead, boolean canDelete) {
        loggedForMarkerGrant(markerGrantId, () -> {
            jdbcClient.sql("UPDATE marker_grant SET item_can_read = :canRead, item_can_delete = :canDelete WHERE id = :markerGrantId")
                    .param(PARAM_CAN_READ, canRead).param("canDelete", canDelete).param(PARAM_MARKER_GRANT_ID, markerGrantId)
                    .update();
            grantsChanged();
        });
    }

    @Override
    public void grantPropertyAccess(UUID markerGrantId, UUID propertyId, boolean canRead, boolean canWrite) {
        loggedForMarkerGrant(markerGrantId, () -> {
            jdbcClient.sql("""
                    INSERT INTO marker_grant_property (marker_grant_id, property_id, can_read, can_write)
                    VALUES (:markerGrantId, :propertyId, :canRead, :canWrite)
                    ON CONFLICT (marker_grant_id, property_id) DO UPDATE
                        SET can_read = EXCLUDED.can_read, can_write = EXCLUDED.can_write
                    """)
                    .param(PARAM_MARKER_GRANT_ID, markerGrantId).param(PARAM_PROPERTY_ID, propertyId)
                    .param(PARAM_CAN_READ, canRead).param(PARAM_CAN_WRITE, canWrite)
                    .update();
            grantsChanged();
        });
    }

    @Override
    public void grantLinkPropertyAccess(UUID markerGrantId, UUID propertyId, boolean canRead, boolean canWrite) {
        loggedForMarkerGrant(markerGrantId, () -> {
            jdbcClient.sql("""
                    INSERT INTO marker_grant_link_property (marker_grant_id, property_id, can_read, can_write)
                    VALUES (:markerGrantId, :propertyId, :canRead, :canWrite)
                    ON CONFLICT (marker_grant_id, property_id) DO UPDATE
                        SET can_read = EXCLUDED.can_read, can_write = EXCLUDED.can_write
                    """)
                    .param(PARAM_MARKER_GRANT_ID, markerGrantId).param(PARAM_PROPERTY_ID, propertyId)
                    .param(PARAM_CAN_READ, canRead).param(PARAM_CAN_WRITE, canWrite)
                    .update();
            grantsChanged();
        });
    }

    // create/read/delete of links reachable through this perspective -- link:read/create/delete are
    // anchored to the *source* item's own marker via a named perspective, never to a marker on the
    // link itself (link markers don't exist -- see docs/ntrloc-marker-admin-ui-design-notes.md).
    @Override
    public void grantLinkPerspectiveAccess(UUID markerGrantId, UUID perspectiveId, boolean canCreate, boolean canRead, boolean canDelete) {
        loggedForMarkerGrant(markerGrantId, () -> {
            jdbcClient.sql("""
                    INSERT INTO marker_grant_link_perspective (marker_grant_id, perspective_id, can_create, can_read, can_delete)
                    VALUES (:markerGrantId, :perspectiveId, :canCreate, :canRead, :canDelete)
                    ON CONFLICT (marker_grant_id, perspective_id) DO UPDATE
                        SET can_create = EXCLUDED.can_create, can_read = EXCLUDED.can_read, can_delete = EXCLUDED.can_delete
                    """)
                    .param(PARAM_MARKER_GRANT_ID, markerGrantId).param(PARAM_PERSPECTIVE_ID, perspectiveId)
                    .param("canCreate", canCreate).param(PARAM_CAN_READ, canRead).param("canDelete", canDelete)
                    .update();
            grantsChanged();
        });
    }

    // Plain existence grant -- execute is a single boolean-shaped verb (see schema comment).
    @Override
    public void grantTransitionExecute(UUID markerGrantId, UUID transitionId) {
        loggedForMarkerGrant(markerGrantId, () -> {
            jdbcClient.sql("""
                    INSERT INTO marker_grant_transition (marker_grant_id, transition_id) VALUES (:markerGrantId, :transitionId)
                    ON CONFLICT DO NOTHING
                    """)
                    .param(PARAM_MARKER_GRANT_ID, markerGrantId).param("transitionId", transitionId)
                    .update();
            grantsChanged();
        });
    }

    @Override
    public void revokeTransitionExecute(UUID markerGrantId, UUID transitionId) {
        loggedForMarkerGrant(markerGrantId, () -> {
            jdbcClient.sql("DELETE FROM marker_grant_transition WHERE marker_grant_id = :markerGrantId AND transition_id = :transitionId")
                    .param(PARAM_MARKER_GRANT_ID, markerGrantId).param("transitionId", transitionId)
                    .update();
            grantsChanged();
        });
    }

    // Plain existence grant -- "start" is a single boolean-shaped verb, like transition:execute.
    @Override
    public void grantStateMachineStart(UUID markerGrantId, UUID stateMachineId) {
        loggedForMarkerGrant(markerGrantId, () -> {
            jdbcClient.sql("""
                    INSERT INTO marker_grant_state_machine_start (marker_grant_id, state_machine_id) VALUES (:markerGrantId, :stateMachineId)
                    ON CONFLICT DO NOTHING
                    """)
                    .param(PARAM_MARKER_GRANT_ID, markerGrantId).param("stateMachineId", stateMachineId)
                    .update();
            grantsChanged();
        });
    }

    @Override
    public void revokeStateMachineStart(UUID markerGrantId, UUID stateMachineId) {
        loggedForMarkerGrant(markerGrantId, () -> {
            jdbcClient.sql("DELETE FROM marker_grant_state_machine_start WHERE marker_grant_id = :markerGrantId AND state_machine_id = :stateMachineId")
                    .param(PARAM_MARKER_GRANT_ID, markerGrantId).param("stateMachineId", stateMachineId)
                    .update();
            grantsChanged();
        });
    }

    // Before a marker is deleted (MarkerStore): its grants go first, here, so the grants view is
    // rebuilt -- rather than leaving them to the database cascade, which this store wouldn't see.
    @Override
    public void deleteGrantsForMarker(UUID markerId) {
        jdbcClient.sql("DELETE FROM marker_grant WHERE marker_id = :markerId").param(PARAM_MARKER_ID, markerId).update();
        grantsChanged();
    }

    // Each node calls this itself on a schema change (SchemaService), so there's nothing to publish.
    @Override
    public void reloadAfterCascade() {
        rebuildGrants();
    }

    // Grant tables refer to principals by a bare UUID, so nothing cascades when a user or group goes.
    @Override
    public void deleteGrantsForPrincipals(String principalType, Collection<UUID> principalIds) {
        if (principalIds.isEmpty()) return;
        for (String table : List.of("authorization_item_type_grant", "marker_grant")) {
            jdbcClient.sql("DELETE FROM " + table + " WHERE principal_type = :principalType AND principal_id IN (:principalIds)")
                    .param("principalType", principalType).param("principalIds", principalIds).update();
        }
        grantsChanged();
    }

    @Override
    public void deleteMarkerGrant(UUID markerGrantId) {
        loggedForMarkerGrant(markerGrantId, () -> {
            jdbcClient.sql("DELETE FROM marker_grant WHERE id = :id")
                    .param("id", markerGrantId).update();
            grantsChanged();
        });
    }

    // Admin-panel read for the marker grant's item-level Read/Delete pair (see ItemLevelAccessRow).
    // Not cache-backed -- low-traffic admin UI, same rationale as getPropertyGrantsForMarker below.
    @Override
    public ItemLevelAccessRow getItemPermissionsForMarker(UUID markerId, String principalType, UUID principalId) {
        return jdbcClient.sql("""
                SELECT item_can_read, item_can_delete FROM marker_grant
                WHERE marker_id = :markerId AND principal_type = :principalType AND principal_id = :principalId
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType).param(PARAM_PRINCIPAL_ID, principalId)
                .query((rs, _) -> new ItemLevelAccessRow(rs.getBoolean("item_can_read"), rs.getBoolean("item_can_delete")))
                .optional()
                .orElse(new ItemLevelAccessRow(false, false));
    }

    @Override
    public Optional<UUID> findMarkerGrant(UUID markerId, String principalType, UUID principalId) {
        return jdbcClient.sql("""
                SELECT id FROM marker_grant WHERE marker_id = :markerId AND principal_type = :principalType AND principal_id = :principalId
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType).param(PARAM_PRINCIPAL_ID, principalId)
                .query(UUID.class).optional();
    }

    // Admin-panel read (low-traffic; a live query, unlike the snapshot-backed grant lookups) -- hydrates the property-grant checkbox grid for one (marker,
    // principal) pair. A property absent from the result has no grant row at all, equivalent to
    // read/write both false.
    @Override
    public List<PropertyAccessRow> getPropertyGrantsForMarker(UUID markerId, String principalType, UUID principalId) {
        return jdbcClient.sql("""
                SELECT mgp.property_id, mgp.can_read, mgp.can_write
                FROM marker_grant_property mgp
                JOIN marker_grant mg ON mg.id = mgp.marker_grant_id
                WHERE mg.marker_id = :markerId AND mg.principal_type = :principalType AND mg.principal_id = :principalId
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType).param(PARAM_PRINCIPAL_ID, principalId)
                .query((rs, _) -> new PropertyAccessRow(
                        rs.getObject(COL_PROPERTY_ID, UUID.class), rs.getBoolean(COL_CAN_READ), rs.getBoolean(COL_CAN_WRITE)))
                .list();
    }

    // Same shape as getPropertyGrantsForMarker, over marker_grant_link_property instead -- a link
    // type's own properties are granted exactly like an item type's, just via a different child table.
    @Override
    public List<PropertyAccessRow> getLinkPropertyGrantsForMarker(UUID markerId, String principalType, UUID principalId) {
        return jdbcClient.sql("""
                SELECT mgp.property_id, mgp.can_read, mgp.can_write
                FROM marker_grant_link_property mgp
                JOIN marker_grant mg ON mg.id = mgp.marker_grant_id
                WHERE mg.marker_id = :markerId AND mg.principal_type = :principalType AND mg.principal_id = :principalId
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType).param(PARAM_PRINCIPAL_ID, principalId)
                .query((rs, _) -> new PropertyAccessRow(
                        rs.getObject(COL_PROPERTY_ID, UUID.class), rs.getBoolean(COL_CAN_READ), rs.getBoolean(COL_CAN_WRITE)))
                .list();
    }

    @Override
    public List<LinkPerspectiveAccessRow> getLinkPerspectiveGrantsForMarker(UUID markerId, String principalType, UUID principalId) {
        return jdbcClient.sql("""
                SELECT mglp.perspective_id, mglp.can_create, mglp.can_read, mglp.can_delete
                FROM marker_grant_link_perspective mglp
                JOIN marker_grant mg ON mg.id = mglp.marker_grant_id
                WHERE mg.marker_id = :markerId AND mg.principal_type = :principalType AND mg.principal_id = :principalId
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType).param(PARAM_PRINCIPAL_ID, principalId)
                .query((rs, _) -> new LinkPerspectiveAccessRow(
                        rs.getObject(COL_PERSPECTIVE_ID, UUID.class), rs.getBoolean("can_create"),
                        rs.getBoolean(COL_CAN_READ), rs.getBoolean("can_delete")))
                .list();
    }

    // Existence-only grant -- a transition either has an execute grant row or it doesn't, no
    // read/write shape (see grantTransitionExecute's own comment).
    @Override
    public Set<UUID> getTransitionGrantsForMarker(UUID markerId, String principalType, UUID principalId) {
        return Set.copyOf(jdbcClient.sql("""
                SELECT mgt.transition_id
                FROM marker_grant_transition mgt
                JOIN marker_grant mg ON mg.id = mgt.marker_grant_id
                WHERE mg.marker_id = :markerId AND mg.principal_type = :principalType AND mg.principal_id = :principalId
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType).param(PARAM_PRINCIPAL_ID, principalId)
                .query((rs, _) -> rs.getObject("transition_id", UUID.class))
                .list());
    }

    // Existence-only, same shape as getTransitionGrantsForMarker -- the granted state-machine ids.
    @Override
    public Set<UUID> getStateMachineStartGrantsForMarker(UUID markerId, String principalType, UUID principalId) {
        return Set.copyOf(jdbcClient.sql("""
                SELECT mgs.state_machine_id
                FROM marker_grant_state_machine_start mgs
                JOIN marker_grant mg ON mg.id = mgs.marker_grant_id
                WHERE mg.marker_id = :markerId AND mg.principal_type = :principalType AND mg.principal_id = :principalId
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType).param(PARAM_PRINCIPAL_ID, principalId)
                .query((rs, _) -> rs.getObject("state_machine_id", UUID.class))
                .list());
    }

    // --- The changelog (docs/ntrloc-system-model-notes.md §13.6): one entry per permission a write changes,
    // found by reading the principal's permissions from the tables before and after. Only the outermost
    // write logs: setPermission's nested writes would otherwise log the same change twice. Deletions of a
    // whole principal or marker are logged by whoever deletes it (UserService, AccessService), as one entry. ---

    private final ThreadLocal<Boolean> logging = new ThreadLocal<>();

    private void logged(String principalType, UUID principalId, Runnable write) {
        if (logging.get() != null) {
            write.run();
            return;
        }
        logging.set(Boolean.TRUE);
        try {
            var principal = new Principal(principalType, principalId);
            Set<Permission> before = permissionsInTables(principal);
            write.run();
            Set<Permission> after = permissionsInTables(principal);
            after.stream().filter(p -> !before.contains(p)).forEach(p -> logGrant("grant.granted", principal, p));
            before.stream().filter(p -> !after.contains(p)).forEach(p -> logGrant("grant.revoked", principal, p));
        } finally {
            logging.remove();
        }
    }

    private void loggedForMarkerGrant(UUID markerGrantId, Runnable write) {
        principalOf("SELECT principal_type, principal_id FROM marker_grant WHERE id = :id", markerGrantId)
                .ifPresentOrElse(p -> logged(p.type(), p.id(), write), write);
    }

    private void loggedForItemTypeGrant(UUID grantId, Runnable write) {
        principalOf("SELECT principal_type, principal_id FROM authorization_item_type_grant WHERE id = :id", grantId)
                .ifPresentOrElse(p -> logged(p.type(), p.id(), write), write);
    }

    private Optional<Principal> principalOf(String sql, UUID id) {
        return jdbcClient.sql(sql).param("id", id)
                .query((rs, _) -> new Principal(rs.getString(COL_PRINCIPAL_TYPE), rs.getObject(COL_PRINCIPAL_ID, UUID.class)))
                .optional();
    }

    private Set<Permission> permissionsInTables(Principal principal) {
        return new GrantsView(grantRows(principal)).permissionsOf(principal.type(), principal.id());
    }

    private void logGrant(String kind, Principal principal, Permission p) {
        Map<String, Object> grant = new java.util.LinkedHashMap<>();
        grant.put("principalType", principal.type());
        grant.put("principalId", principal.id());
        grant.put("permission", p);
        changeLog.append(kind, "grant:" + principal.type() + ":" + principal.id() + ":" + permissionKey(p),
                java.util.Arrays.asList(principal.id(), p.itemTypeId(), p.markerId(), p.targetId()),
                kind.equals("grant.revoked") ? grant : null, kind.equals("grant.granted") ? grant : null);
    }

    // The same key the admin UI uses (grantModel.permissionKey).
    private static String permissionKey(Permission p) {
        return java.util.stream.Stream.of(p.kind(), p.itemTypeId(), p.markerId(), p.area(), p.targetId(), p.op())
                .map(v -> v == null ? "" : v.toString()).collect(java.util.stream.Collectors.joining("|"));
    }

    // --- Bulk reads: the snapshot rebuild, and the access admin overview (item-type and marker grants) ---

    // One principal, to narrow the loaders below; null for every principal.
    private record Principal(String type, UUID id) {}

    private JdbcClient.StatementSpec rows(String sql, String alias, Principal only) {
        if (only == null) return jdbcClient.sql(sql);
        return jdbcClient.sql(sql + " WHERE " + alias + "principal_type = :onlyType AND " + alias + "principal_id = :onlyId")
                .param("onlyType", only.type()).param("onlyId", only.id());
    }

    @Override
    public List<ItemTypeGrantRow> getAllItemTypeGrants() {
        return itemTypeGrants(null);
    }

    @Override
    public List<MarkerGrantRow> getAllMarkerGrants() {
        return markerGrants(null);
    }

    private List<ItemTypeGrantRow> itemTypeGrants(Principal only) {
        return rows("SELECT principal_type, principal_id, permission, item_type_id FROM authorization_item_type_grant", "", only)
                .query((rs, _) -> new ItemTypeGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE),
                        rs.getObject(COL_PRINCIPAL_ID, UUID.class),
                        rs.getString(COL_PERMISSION),
                        rs.getObject(COL_ITEM_TYPE_ID, UUID.class)))
                .list();
    }

    private List<MarkerGrantRow> markerGrants(Principal only) {
        return rows("SELECT principal_type, principal_id, marker_id, item_can_read, item_can_delete FROM marker_grant", "", only)
                .query((rs, _) -> new MarkerGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE),
                        rs.getObject(COL_PRINCIPAL_ID, UUID.class),
                        rs.getObject(COL_MARKER_ID, UUID.class),
                        rs.getBoolean("item_can_read"),
                        rs.getBoolean("item_can_delete")))
                .list();
    }

    private List<PropertyGrantRow> propertyGrants(Principal only) {
        return rows("""
                SELECT mg.principal_type, mg.principal_id, mg.marker_id, mgp.property_id, mgp.can_read, mgp.can_write
                FROM marker_grant_property mgp JOIN marker_grant mg ON mg.id = mgp.marker_grant_id""", "mg.", only)
                .query((rs, _) -> new PropertyGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE), rs.getObject(COL_PRINCIPAL_ID, UUID.class), rs.getObject(COL_MARKER_ID, UUID.class),
                        rs.getObject(COL_PROPERTY_ID, UUID.class), rs.getBoolean(COL_CAN_READ), rs.getBoolean(COL_CAN_WRITE)))
                .list();
    }

    private List<PropertyGrantRow> linkPropertyGrants(Principal only) {
        return rows("""
                SELECT mg.principal_type, mg.principal_id, mg.marker_id, mgp.property_id, mgp.can_read, mgp.can_write
                FROM marker_grant_link_property mgp JOIN marker_grant mg ON mg.id = mgp.marker_grant_id""", "mg.", only)
                .query((rs, _) -> new PropertyGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE), rs.getObject(COL_PRINCIPAL_ID, UUID.class), rs.getObject(COL_MARKER_ID, UUID.class),
                        rs.getObject(COL_PROPERTY_ID, UUID.class), rs.getBoolean(COL_CAN_READ), rs.getBoolean(COL_CAN_WRITE)))
                .list();
    }

    private List<LinkPerspectiveGrantRow> linkPerspectiveGrants(Principal only) {
        return rows("""
                SELECT mg.principal_type, mg.principal_id, mg.marker_id, mglp.perspective_id, mglp.can_create, mglp.can_read, mglp.can_delete
                FROM marker_grant_link_perspective mglp JOIN marker_grant mg ON mg.id = mglp.marker_grant_id""", "mg.", only)
                .query((rs, _) -> new LinkPerspectiveGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE), rs.getObject(COL_PRINCIPAL_ID, UUID.class), rs.getObject(COL_MARKER_ID, UUID.class),
                        rs.getObject(COL_PERSPECTIVE_ID, UUID.class), rs.getBoolean("can_create"), rs.getBoolean(COL_CAN_READ), rs.getBoolean("can_delete")))
                .list();
    }

    private List<MarkerScopedGrantRow> transitionGrants(Principal only) {
        return rows("""
                SELECT mg.principal_type, mg.principal_id, mg.marker_id, mgt.transition_id
                FROM marker_grant_transition mgt JOIN marker_grant mg ON mg.id = mgt.marker_grant_id""", "mg.", only)
                .query((rs, _) -> new MarkerScopedGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE), rs.getObject(COL_PRINCIPAL_ID, UUID.class),
                        rs.getObject(COL_MARKER_ID, UUID.class), rs.getObject("transition_id", UUID.class)))
                .list();
    }

    private List<MarkerScopedGrantRow> stateMachineStartGrants(Principal only) {
        return rows("""
                SELECT mg.principal_type, mg.principal_id, mg.marker_id, mgs.state_machine_id
                FROM marker_grant_state_machine_start mgs JOIN marker_grant mg ON mg.id = mgs.marker_grant_id""", "mg.", only)
                .query((rs, _) -> new MarkerScopedGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE), rs.getObject(COL_PRINCIPAL_ID, UUID.class),
                        rs.getObject(COL_MARKER_ID, UUID.class), rs.getObject("state_machine_id", UUID.class)))
                .list();
    }

    private GrantsView.Rows grantRows(Principal only) {
        return new GrantsView.Rows(itemTypeGrants(only), markerGrants(only), propertyGrants(only), linkPropertyGrants(only),
                linkPerspectiveGrants(only), transitionGrants(only), stateMachineStartGrants(only));
    }
}

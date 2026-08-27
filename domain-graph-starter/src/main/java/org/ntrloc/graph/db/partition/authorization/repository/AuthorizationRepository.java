package org.ntrloc.graph.db.partition.authorization.repository;

import org.ntrloc.graph.db.partition.authorization.AuthorizationCacheManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class AuthorizationRepository {

    private static final String COL_ITEM_TYPE_ID = "item_type_id";
    private static final String COL_PERMISSION = "permission";
    private static final String COL_MARKER_ID = "marker_id";
    private static final String COL_PRINCIPAL_TYPE = "principal_type";
    private static final String COL_PRINCIPAL_ID = "principal_id";
    private static final String COL_PROPERTY_ID = "property_id";
    private static final String COL_PERSPECTIVE_ID = "perspective_id";
    private static final String COL_CAN_READ = "can_read";
    private static final String COL_CAN_WRITE = "can_write";
    private static final String COL_CAN_DOWNLOAD = "can_download";
    private static final String PARAM_ITEM_TYPE_ID = "itemTypeId";
    private static final String PARAM_PRINCIPAL_TYPE = "principalType";
    private static final String PARAM_PRINCIPAL_ID = "principalId";
    private static final String PARAM_PERMISSION = "permission";
    private static final String PARAM_MARKER_ID = "markerId";
    private static final String PARAM_MARKER_GRANT_ID = "markerGrantId";
    private static final String PARAM_ITEM_ID = "itemId";
    private static final String PARAM_PROPERTY_ID = "propertyId";
    private static final String PARAM_PERSPECTIVE_ID = "perspectiveId";
    private static final String PARAM_CAN_READ = "canRead";
    private static final String PARAM_CAN_WRITE = "canWrite";
    private static final String PARAM_CAN_DOWNLOAD = "canDownload";

    public record GrantRow(UUID grantId, UUID itemTypeId, String itemTypeName, String permission) {}

    public record MarkerRow(UUID id, String name, String description, String scopeKind, UUID scopeId) {}

    // Raw rows for AuthorizationCacheManager.rebuildCache() -- see the getAll* methods below.
    public record ItemTypeGrantRow(String principalType, UUID principalId, String permission, UUID itemTypeId) {}

    public record MarkerGrantRow(String principalType, UUID principalId, UUID markerId, boolean itemCanRead, boolean itemCanDelete) {}

    public record PropertyGrantRow(String principalType, UUID principalId, UUID markerId, UUID propertyId, boolean canRead, boolean canWrite) {}

    public record LinkPerspectiveGrantRow(String principalType, UUID principalId, UUID markerId, UUID perspectiveId, boolean canRead, boolean canDelete) {}

    private final JdbcClient jdbcClient;
    private final AuthorizationCacheManager cacheManager;

    // AuthorizationCacheManager rebuilds itself FROM this repository (getAllItemTypeGrants/
    // getAllMarkerGrants/etc. below), so a plain constructor-injected reference back here would be a
    // circular bean dependency. @Lazy breaks it with a deferred-resolution proxy -- standard
    // Spring technique for exactly this shape (mirrors how a cache sits "above" the store it
    // invalidates, e.g. SchemaManager's own refreshCache pattern, just split across two beans
    // here since the cache also needs its own Hazelcast plumbing).
    public AuthorizationRepository(JdbcClient jdbcClient, @Lazy AuthorizationCacheManager cacheManager) {
        this.jdbcClient = jdbcClient;
        this.cacheManager = cacheManager;
    }

    // --- Direct item-type grant writes (reads are cache-backed -- see AuthorizationCacheManager) ---

    public void grantItemType(UUID itemTypeId, String principalType, UUID principalId, String permission) {
        jdbcClient.sql("""
                INSERT INTO authorization_item_type_grant (item_type_id, principal_type, principal_id, permission)
                VALUES (:itemTypeId, :principalType, :principalId, :permission)
                """)
                .param(PARAM_ITEM_TYPE_ID, itemTypeId).param(PARAM_PRINCIPAL_TYPE, principalType)
                .param(PARAM_PRINCIPAL_ID, principalId).param(PARAM_PERMISSION, permission)
                .update();
        cacheManager.refreshCache();
    }

    public void grantItemTypeIfAbsent(UUID itemTypeId, String principalType, UUID principalId, String permission) {
        jdbcClient.sql("""
                INSERT INTO authorization_item_type_grant (id, item_type_id, principal_type, principal_id, permission)
                VALUES (gen_random_uuid(), :itemTypeId, :principalType, :principalId, :permission)
                ON CONFLICT DO NOTHING
                """)
                .param(PARAM_ITEM_TYPE_ID, itemTypeId).param(PARAM_PRINCIPAL_TYPE, principalType)
                .param(PARAM_PRINCIPAL_ID, principalId).param(PARAM_PERMISSION, permission)
                .update();
        cacheManager.refreshCache();
    }

    public Optional<UUID> findItemTypeGrant(UUID itemTypeId, String principalType, UUID principalId, String permission) {
        return jdbcClient.sql("""
                SELECT id FROM authorization_item_type_grant
                WHERE item_type_id = :itemTypeId AND principal_type = :principalType
                  AND principal_id = :principalId AND permission = :permission
                """)
                .param(PARAM_ITEM_TYPE_ID, itemTypeId).param(PARAM_PRINCIPAL_TYPE, principalType)
                .param(PARAM_PRINCIPAL_ID, principalId).param(PARAM_PERMISSION, permission)
                .query((rs, n) -> rs.getObject("id", UUID.class))
                .optional();
    }

    public void deleteItemTypeGrant(UUID grantId) {
        jdbcClient.sql("DELETE FROM authorization_item_type_grant WHERE id = :id")
                .param("id", grantId).update();
        cacheManager.refreshCache();
    }

    // --- Admin permission reads (low-traffic admin-panel listings; not cached) ---

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
                .query((rs, n) -> new GrantRow(
                        rs.getObject("grant_id", UUID.class),
                        rs.getObject(COL_ITEM_TYPE_ID, UUID.class),
                        rs.getString("item_type_name"),
                        rs.getString(COL_PERMISSION)))
                .list();
    }

    public List<GrantRow> getItemTypeGrantsForGroups(Set<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                SELECT g.id AS grant_id, g.item_type_id, si.name AS item_type_name, g.permission
                FROM authorization_item_type_grant g
                JOIN schema_item si ON si.id = g.item_type_id
                WHERE g.principal_type = 'GROUP' AND g.principal_id IN (:groupIds)
                ORDER BY si.name, g.permission
                """)
                .param("groupIds", groupIds)
                .query((rs, n) -> new GrantRow(
                        rs.getObject("grant_id", UUID.class),
                        rs.getObject(COL_ITEM_TYPE_ID, UUID.class),
                        rs.getString("item_type_name"),
                        rs.getString(COL_PERMISSION)))
                .list();
    }

    // --- Markers ---
    // scope_kind/scope_id are mandatory at creation -- a marker's scope is what makes its property/
    // link-perspective grants meaningful at all (see docs/ntrloc-marker-admin-ui-design-notes.md).
    // Deeper validation (does scope_id actually resolve to a real item/trait/perspective, are
    // referenced properties/perspectives actually within scope) is deliberately not implemented yet --
    // this pass is the schema/grant-shape refactor; scope *enforcement* is later, separately-scoped
    // work per the design doc's own sequencing.

    public MarkerRow createMarker(String name, String description, String scopeKind, UUID scopeId) {
        UUID id = jdbcClient.sql("""
                INSERT INTO authorization_marker (name, description, scope_kind, scope_id)
                VALUES (:name, :description, :scopeKind, :scopeId) RETURNING id
                """)
                .param("name", name).param("description", description)
                .param("scopeKind", scopeKind).param("scopeId", scopeId)
                .query(UUID.class).single();
        return new MarkerRow(id, name, description, scopeKind, scopeId);
    }

    /** Every marker id assigned to this item, for the batched per-page lookup used to resolve mode-2 (property/capability) checks. */
    public Set<UUID> getMarkerIdsForItem(UUID itemId) {
        return Set.copyOf(jdbcClient.sql("""
                SELECT rim.marker_id FROM register_item_marker rim
                JOIN register_item ri ON ri.id = rim.register_item_id
                WHERE ri.item_id = :itemId AND ri.state = 'COMMITTED'
                """)
                .param(PARAM_ITEM_ID, itemId)
                .query((rs, n) -> rs.getObject(COL_MARKER_ID, UUID.class))
                .list());
    }

    /** Marker ids per item for a whole page at once -- one query, not one per item. */
    public Map<UUID, Set<UUID>> getMarkerIdsForItems(Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return jdbcClient.sql("""
                SELECT ri.item_id AS item_id, rim.marker_id FROM register_item_marker rim
                JOIN register_item ri ON ri.id = rim.register_item_id
                WHERE ri.item_id IN (:itemIds) AND ri.state = 'COMMITTED'
                """)
                .param("itemIds", itemIds)
                .query((rs, n) -> Map.entry(rs.getObject("item_id", UUID.class), rs.getObject(COL_MARKER_ID, UUID.class)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toSet())));
    }

    // --- Marker grant writes (reads are cache-backed -- see AuthorizationCacheManager) ---
    // One marker_grant row per (marker, principal) pair -- item_can_read/item_can_delete live
    // directly on it (strictly 1:1: the only "item" a grant's item-level verbs ever reference is
    // whatever item carries the marker). Everything else (properties, link perspectives, link
    // properties, transitions) is genuinely one-to-many per grant and gets its own child table.

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

    public void setItemPermissions(UUID markerGrantId, boolean canRead, boolean canDelete) {
        jdbcClient.sql("UPDATE marker_grant SET item_can_read = :canRead, item_can_delete = :canDelete WHERE id = :markerGrantId")
                .param(PARAM_CAN_READ, canRead).param("canDelete", canDelete).param(PARAM_MARKER_GRANT_ID, markerGrantId)
                .update();
        cacheManager.refreshCache();
    }

    public void grantPropertyAccess(UUID markerGrantId, UUID propertyId, boolean canRead, boolean canWrite, boolean canDownload) {
        jdbcClient.sql("""
                INSERT INTO marker_grant_property (marker_grant_id, property_id, can_read, can_write, can_download)
                VALUES (:markerGrantId, :propertyId, :canRead, :canWrite, :canDownload)
                ON CONFLICT (marker_grant_id, property_id) DO UPDATE
                    SET can_read = EXCLUDED.can_read, can_write = EXCLUDED.can_write, can_download = EXCLUDED.can_download
                """)
                .param(PARAM_MARKER_GRANT_ID, markerGrantId).param(PARAM_PROPERTY_ID, propertyId)
                .param(PARAM_CAN_READ, canRead).param(PARAM_CAN_WRITE, canWrite).param(PARAM_CAN_DOWNLOAD, canDownload)
                .update();
        cacheManager.refreshCache();
    }

    public void grantLinkPropertyAccess(UUID markerGrantId, UUID propertyId, boolean canRead, boolean canWrite, boolean canDownload) {
        jdbcClient.sql("""
                INSERT INTO marker_grant_link_property (marker_grant_id, property_id, can_read, can_write, can_download)
                VALUES (:markerGrantId, :propertyId, :canRead, :canWrite, :canDownload)
                ON CONFLICT (marker_grant_id, property_id) DO UPDATE
                    SET can_read = EXCLUDED.can_read, can_write = EXCLUDED.can_write, can_download = EXCLUDED.can_download
                """)
                .param(PARAM_MARKER_GRANT_ID, markerGrantId).param(PARAM_PROPERTY_ID, propertyId)
                .param(PARAM_CAN_READ, canRead).param(PARAM_CAN_WRITE, canWrite).param(PARAM_CAN_DOWNLOAD, canDownload)
                .update();
        cacheManager.refreshCache();
    }

    // create/read/delete of links reachable through this perspective -- link:read/create/delete are
    // anchored to the *source* item's own marker via a named perspective, never to a marker on the
    // link itself (link markers don't exist -- see docs/ntrloc-marker-admin-ui-design-notes.md).
    public void grantLinkPerspectiveAccess(UUID markerGrantId, UUID perspectiveId, boolean canCreate, boolean canRead, boolean canDelete) {
        jdbcClient.sql("""
                INSERT INTO marker_grant_link_perspective (marker_grant_id, perspective_id, can_create, can_read, can_delete)
                VALUES (:markerGrantId, :perspectiveId, :canCreate, :canRead, :canDelete)
                ON CONFLICT (marker_grant_id, perspective_id) DO UPDATE
                    SET can_create = EXCLUDED.can_create, can_read = EXCLUDED.can_read, can_delete = EXCLUDED.can_delete
                """)
                .param(PARAM_MARKER_GRANT_ID, markerGrantId).param(PARAM_PERSPECTIVE_ID, perspectiveId)
                .param("canCreate", canCreate).param(PARAM_CAN_READ, canRead).param("canDelete", canDelete)
                .update();
        cacheManager.refreshCache();
    }

    // Plain existence grant -- execute is a single boolean-shaped verb (see schema comment).
    public void grantTransitionExecute(UUID markerGrantId, UUID transitionId) {
        jdbcClient.sql("""
                INSERT INTO marker_grant_transition (marker_grant_id, transition_id) VALUES (:markerGrantId, :transitionId)
                ON CONFLICT DO NOTHING
                """)
                .param(PARAM_MARKER_GRANT_ID, markerGrantId).param("transitionId", transitionId)
                .update();
        cacheManager.refreshCache();
    }

    public void revokeTransitionExecute(UUID markerGrantId, UUID transitionId) {
        jdbcClient.sql("DELETE FROM marker_grant_transition WHERE marker_grant_id = :markerGrantId AND transition_id = :transitionId")
                .param(PARAM_MARKER_GRANT_ID, markerGrantId).param("transitionId", transitionId)
                .update();
        cacheManager.refreshCache();
    }

    public void deleteMarkerGrant(UUID markerGrantId) {
        jdbcClient.sql("DELETE FROM marker_grant WHERE id = :id")
                .param("id", markerGrantId).update();
        cacheManager.refreshCache();
    }

    public Optional<UUID> findMarkerGrant(UUID markerId, String principalType, UUID principalId) {
        return jdbcClient.sql("""
                SELECT id FROM marker_grant WHERE marker_id = :markerId AND principal_type = :principalType AND principal_id = :principalId
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType).param(PARAM_PRINCIPAL_ID, principalId)
                .query(UUID.class).optional();
    }

    // --- Bulk reads for AuthorizationCacheManager.rebuildCache() -- never called elsewhere ---

    public List<ItemTypeGrantRow> getAllItemTypeGrants() {
        return jdbcClient.sql("SELECT principal_type, principal_id, permission, item_type_id FROM authorization_item_type_grant")
                .query((rs, n) -> new ItemTypeGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE),
                        rs.getObject(COL_PRINCIPAL_ID, UUID.class),
                        rs.getString(COL_PERMISSION),
                        rs.getObject(COL_ITEM_TYPE_ID, UUID.class)))
                .list();
    }

    public List<MarkerGrantRow> getAllMarkerGrants() {
        return jdbcClient.sql("SELECT principal_type, principal_id, marker_id, item_can_read, item_can_delete FROM marker_grant")
                .query((rs, n) -> new MarkerGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE),
                        rs.getObject(COL_PRINCIPAL_ID, UUID.class),
                        rs.getObject(COL_MARKER_ID, UUID.class),
                        rs.getBoolean("item_can_read"),
                        rs.getBoolean("item_can_delete")))
                .list();
    }

    public List<PropertyGrantRow> getAllPropertyGrants() {
        return jdbcClient.sql("""
                SELECT mg.principal_type, mg.principal_id, mg.marker_id, mgp.property_id, mgp.can_read, mgp.can_write
                FROM marker_grant_property mgp JOIN marker_grant mg ON mg.id = mgp.marker_grant_id
                """)
                .query((rs, n) -> new PropertyGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE), rs.getObject(COL_PRINCIPAL_ID, UUID.class), rs.getObject(COL_MARKER_ID, UUID.class),
                        rs.getObject(COL_PROPERTY_ID, UUID.class), rs.getBoolean(COL_CAN_READ), rs.getBoolean(COL_CAN_WRITE)))
                .list();
    }

    public List<PropertyGrantRow> getAllLinkPropertyGrants() {
        return jdbcClient.sql("""
                SELECT mg.principal_type, mg.principal_id, mg.marker_id, mgp.property_id, mgp.can_read, mgp.can_write
                FROM marker_grant_link_property mgp JOIN marker_grant mg ON mg.id = mgp.marker_grant_id
                """)
                .query((rs, n) -> new PropertyGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE), rs.getObject(COL_PRINCIPAL_ID, UUID.class), rs.getObject(COL_MARKER_ID, UUID.class),
                        rs.getObject(COL_PROPERTY_ID, UUID.class), rs.getBoolean(COL_CAN_READ), rs.getBoolean(COL_CAN_WRITE)))
                .list();
    }

    public List<LinkPerspectiveGrantRow> getAllLinkPerspectiveGrants() {
        return jdbcClient.sql("""
                SELECT mg.principal_type, mg.principal_id, mg.marker_id, mglp.perspective_id, mglp.can_read, mglp.can_delete
                FROM marker_grant_link_perspective mglp JOIN marker_grant mg ON mg.id = mglp.marker_grant_id
                """)
                .query((rs, n) -> new LinkPerspectiveGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE), rs.getObject(COL_PRINCIPAL_ID, UUID.class), rs.getObject(COL_MARKER_ID, UUID.class),
                        rs.getObject(COL_PERSPECTIVE_ID, UUID.class), rs.getBoolean(COL_CAN_READ), rs.getBoolean("can_delete")))
                .list();
    }
}

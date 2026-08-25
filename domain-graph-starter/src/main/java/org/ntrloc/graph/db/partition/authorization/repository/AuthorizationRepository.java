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
    private static final String COL_OPERATION = "operation";
    private static final String COL_PROPERTY_ID = "property_id";
    private static final String PARAM_ITEM_TYPE_ID = "itemTypeId";
    private static final String PARAM_PRINCIPAL_TYPE = "principalType";
    private static final String PARAM_PRINCIPAL_ID = "principalId";
    private static final String PARAM_PERMISSION = "permission";
    private static final String PARAM_OPERATION = "operation";
    private static final String PARAM_MARKER_ID = "markerId";
    private static final String PARAM_ITEM_ID = "itemId";
    private static final String PARAM_LINK_ID = "linkId";

    public record GrantRow(UUID grantId, UUID itemTypeId, String itemTypeName, String permission) {}

    public record MarkerRow(UUID id, String name, String description) {}

    // Raw rows for AuthorizationCacheManager.rebuildCache() -- see the two getAll* methods below.
    public record ItemTypeGrantRow(String principalType, UUID principalId, String permission, UUID itemTypeId) {}

    public record MarkerGrantRow(String principalType, UUID principalId, String operation, UUID markerId, UUID propertyId) {}

    private final JdbcClient jdbcClient;
    private final AuthorizationCacheManager cacheManager;

    // AuthorizationCacheManager rebuilds itself FROM this repository (getAllItemTypeGrants/
    // getAllMarkerGrants below), so a plain constructor-injected reference back here would be a
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

    /**
     * Returns all direct item-type grants for a given principal type + id, joined with schema_item for display.
     */
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

    /**
     * Returns all direct item-type grants for a set of group ids, joined with schema_item for display.
     * Used to compute effective user permissions.
     */
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

    public MarkerRow createMarker(String name, String description) {
        UUID id = jdbcClient.sql("INSERT INTO authorization_marker (name, description) VALUES (:name, :description) RETURNING id")
                .param("name", name).param("description", description)
                .query(UUID.class).single();
        return new MarkerRow(id, name, description);
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

    public Set<UUID> getMarkerIdsForLink(UUID linkId) {
        return Set.copyOf(jdbcClient.sql("""
                SELECT rlm.marker_id FROM register_link_marker rlm
                JOIN register_link rl ON rl.id = rlm.register_link_id
                WHERE rl.link_id = :linkId AND rl.state = 'COMMITTED'
                """)
                .param(PARAM_LINK_ID, linkId)
                .query((rs, n) -> rs.getObject(COL_MARKER_ID, UUID.class))
                .list());
    }

    public Map<UUID, Set<UUID>> getMarkerIdsForLinks(Set<UUID> linkIds) {
        if (linkIds.isEmpty()) {
            return Map.of();
        }
        return jdbcClient.sql("""
                SELECT rl.link_id AS link_id, rlm.marker_id FROM register_link_marker rlm
                JOIN register_link rl ON rl.id = rlm.register_link_id
                WHERE rl.link_id IN (:linkIds) AND rl.state = 'COMMITTED'
                """)
                .param("linkIds", linkIds)
                .query((rs, n) -> Map.entry(rs.getObject(PARAM_LINK_ID, UUID.class), rs.getObject(COL_MARKER_ID, UUID.class)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toSet())));
    }

    // --- Marker grant writes (property_id NULL for item:read/item:delete/link:read/link:delete;
    // required for property:read/property:write/link_property:read/link_property:write). Reads are
    // cache-backed -- see AuthorizationCacheManager. ---

    public void grantMarker(UUID markerId, String principalType, UUID principalId, String operation, UUID propertyId) {
        jdbcClient.sql("""
                INSERT INTO authorization_grant (marker_id, principal_type, principal_id, operation, property_id)
                VALUES (:markerId, :principalType, :principalId, :operation, :propertyId)
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType)
                .param(PARAM_PRINCIPAL_ID, principalId).param(PARAM_OPERATION, operation)
                .param("propertyId", propertyId)
                .update();
        cacheManager.refreshCache();
    }

    public void grantMarkerIfAbsent(UUID markerId, String principalType, UUID principalId, String operation, UUID propertyId) {
        jdbcClient.sql("""
                INSERT INTO authorization_grant (id, marker_id, principal_type, principal_id, operation, property_id)
                VALUES (gen_random_uuid(), :markerId, :principalType, :principalId, :operation, :propertyId)
                ON CONFLICT DO NOTHING
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType)
                .param(PARAM_PRINCIPAL_ID, principalId).param(PARAM_OPERATION, operation)
                .param("propertyId", propertyId)
                .update();
        cacheManager.refreshCache();
    }

    public Optional<UUID> findMarkerGrant(UUID markerId, String principalType, UUID principalId, String operation, UUID propertyId) {
        return jdbcClient.sql("""
                SELECT id FROM authorization_grant
                WHERE marker_id = :markerId AND principal_type = :principalType AND principal_id = :principalId
                  AND operation = :operation AND property_id IS NOT DISTINCT FROM :propertyId
                """)
                .param(PARAM_MARKER_ID, markerId).param(PARAM_PRINCIPAL_TYPE, principalType)
                .param(PARAM_PRINCIPAL_ID, principalId).param(PARAM_OPERATION, operation)
                .param("propertyId", propertyId)
                .query((rs, n) -> rs.getObject("id", UUID.class))
                .optional();
    }

    public void deleteMarkerGrant(UUID grantId) {
        jdbcClient.sql("DELETE FROM authorization_grant WHERE id = :id")
                .param("id", grantId).update();
        cacheManager.refreshCache();
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
        return jdbcClient.sql("SELECT principal_type, principal_id, operation, marker_id, property_id FROM authorization_grant")
                .query((rs, n) -> new MarkerGrantRow(
                        rs.getString(COL_PRINCIPAL_TYPE),
                        rs.getObject(COL_PRINCIPAL_ID, UUID.class),
                        rs.getString(COL_OPERATION),
                        rs.getObject(COL_MARKER_ID, UUID.class),
                        rs.getObject(COL_PROPERTY_ID, UUID.class)))
                .list();
    }
}

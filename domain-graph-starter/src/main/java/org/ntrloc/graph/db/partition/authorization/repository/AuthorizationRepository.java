package org.ntrloc.graph.db.partition.authorization.repository;

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

    // Sentinel used only to keep "IN (:groupIds)" valid SQL when a principal belongs to
    // no groups — a real uuidv7()-generated id can never collide with this value.
    private static final UUID NO_GROUPS_SENTINEL = new UUID(0L, 0L);

    private static final String COL_ITEM_TYPE_ID = "item_type_id";
    private static final String COL_MARKER_ID = "marker_id";
    private static final String COL_OPERATION = "operation";
    private static final String PARAM_MARKER_ID = "markerId";
    private static final String PARAM_ITEM_TYPE_ID = "itemTypeId";

    public record MarkerRow(UUID id, String name, String description) {}

    private final JdbcClient jdbcClient;

    public AuthorizationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // --- Marker assignment reads ---

    public Map<UUID, List<UUID>> getMarkerIdsByItemType() {
        return jdbcClient.sql("SELECT item_type_id, marker_id FROM authorization_item_type_marker")
                .query((rs, n) -> Map.entry(
                        rs.getObject(COL_ITEM_TYPE_ID, UUID.class),
                        rs.getObject(COL_MARKER_ID, UUID.class)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    // --- Grant reads ---

    public Set<UUID> getGrantedMarkerIds(UUID userId, Set<UUID> groupIds, String operation) {
        Set<UUID> safeGroupIds = groupIds.isEmpty() ? Set.of(NO_GROUPS_SENTINEL) : groupIds;
        return Set.copyOf(jdbcClient.sql("""
                SELECT DISTINCT marker_id FROM authorization_grant
                WHERE operation = :operation
                  AND ((principal_type = 'USER'  AND principal_id = :userId)
                    OR (principal_type = 'GROUP' AND principal_id IN (:groupIds)))
                """)
                .param(COL_OPERATION, operation)
                .param("userId", userId)
                .param("groupIds", safeGroupIds)
                .query((rs, n) -> rs.getObject(COL_MARKER_ID, UUID.class))
                .list());
    }

    // --- Test-data seeding writes ---

    public MarkerRow createMarker(String name, String description) {
        UUID id = jdbcClient.sql("INSERT INTO authorization_marker (name, description) VALUES (:name, :description) RETURNING id")
                .param("name", name).param("description", description)
                .query(UUID.class).single();
        return new MarkerRow(id, name, description);
    }

    public void assignMarkerToItemType(UUID itemTypeId, UUID markerId) {
        jdbcClient.sql("INSERT INTO authorization_item_type_marker (item_type_id, marker_id) VALUES (:itemTypeId, :markerId)")
                .param(PARAM_ITEM_TYPE_ID, itemTypeId).param(PARAM_MARKER_ID, markerId).update();
    }

    public void grant(UUID markerId, String principalType, UUID principalId, String operation) {
        jdbcClient.sql("""
                INSERT INTO authorization_grant (marker_id, principal_type, principal_id, operation)
                VALUES (:markerId, :principalType, :principalId, :operation)
                """)
                .param(PARAM_MARKER_ID, markerId).param("principalType", principalType)
                .param("principalId", principalId).param(COL_OPERATION, operation)
                .update();
    }

    // --- Admin permission reads ---

    public record GrantRow(UUID grantId, UUID markerId, String markerName, UUID itemTypeId, String itemTypeName, String operation) {}

    /**
     * Returns all grants for a given principal type + id, joined through marker -> item_type_marker -> schema_item.
     */
    public List<GrantRow> getGrantsForPrincipal(String principalType, UUID principalId) {
        return jdbcClient.sql("""
                SELECT g.id AS grant_id, g.marker_id, m.name AS marker_name,
                       itm.item_type_id, si.name AS item_type_name, g.operation
                FROM authorization_grant g
                JOIN authorization_marker m ON m.id = g.marker_id
                JOIN authorization_item_type_marker itm ON itm.marker_id = g.marker_id
                JOIN schema_item si ON si.id = itm.item_type_id
                WHERE g.principal_type = :principalType AND g.principal_id = :principalId
                ORDER BY si.name, g.operation
                """)
                .param("principalType", principalType)
                .param("principalId", principalId)
                .query((rs, n) -> new GrantRow(
                        rs.getObject("grant_id", UUID.class),
                        rs.getObject(COL_MARKER_ID, UUID.class),
                        rs.getString("marker_name"),
                        rs.getObject(COL_ITEM_TYPE_ID, UUID.class),
                        rs.getString("item_type_name"),
                        rs.getString(COL_OPERATION)))
                .list();
    }

    /**
     * Returns all grants for a set of group ids, joined through marker -> item_type_marker -> schema_item.
     * Used to compute effective user permissions.
     */
    public List<GrantRow> getGrantsForGroups(Set<UUID> groupIds) {
        if (groupIds.isEmpty()) return List.of();
        return jdbcClient.sql("""
                SELECT g.id AS grant_id, g.marker_id, m.name AS marker_name,
                       itm.item_type_id, si.name AS item_type_name, g.operation
                FROM authorization_grant g
                JOIN authorization_marker m ON m.id = g.marker_id
                JOIN authorization_item_type_marker itm ON itm.marker_id = g.marker_id
                JOIN schema_item si ON si.id = itm.item_type_id
                WHERE g.principal_type = 'GROUP' AND g.principal_id IN (:groupIds)
                ORDER BY si.name, g.operation
                """)
                .param("groupIds", groupIds)
                .query((rs, n) -> new GrantRow(
                        rs.getObject("grant_id", UUID.class),
                        rs.getObject(COL_MARKER_ID, UUID.class),
                        rs.getString("marker_name"),
                        rs.getObject(COL_ITEM_TYPE_ID, UUID.class),
                        rs.getString("item_type_name"),
                        rs.getString(COL_OPERATION)))
                .list();
    }

    /**
     * Find a marker that is assigned to the given item type (by item_type_id).
     */
    public Optional<UUID> findMarkerForItemType(UUID itemTypeId) {
        return jdbcClient.sql("SELECT marker_id FROM authorization_item_type_marker WHERE item_type_id = :itemTypeId LIMIT 1")
                .param(PARAM_ITEM_TYPE_ID, itemTypeId)
                .query((rs, n) -> rs.getObject(COL_MARKER_ID, UUID.class))
                .optional();
    }

    /**
     * Find a grant matching the exact principal + marker + operation combination.
     */
    public Optional<UUID> findGrant(UUID markerId, String principalType, UUID principalId, String operation) {
        return jdbcClient.sql("""
                SELECT id FROM authorization_grant
                WHERE marker_id = :markerId AND principal_type = :principalType
                  AND principal_id = :principalId AND operation = :operation
                """)
                .param(PARAM_MARKER_ID, markerId).param("principalType", principalType)
                .param("principalId", principalId).param(COL_OPERATION, operation)
                .query((rs, n) -> rs.getObject("id", UUID.class))
                .optional();
    }

    public void deleteGrant(UUID grantId) {
        jdbcClient.sql("DELETE FROM authorization_grant WHERE id = :id")
                .param("id", grantId).update();
    }

    public void assignMarkerToItemTypeIfAbsent(UUID itemTypeId, UUID markerId) {
        jdbcClient.sql("""
                INSERT INTO authorization_item_type_marker (item_type_id, marker_id)
                VALUES (:itemTypeId, :markerId) ON CONFLICT DO NOTHING
                """)
                .param(PARAM_ITEM_TYPE_ID, itemTypeId).param(PARAM_MARKER_ID, markerId).update();
    }

    public void grantIfAbsent(UUID markerId, String principalType, UUID principalId, String operation) {
        jdbcClient.sql("""
                INSERT INTO authorization_grant (id, marker_id, principal_type, principal_id, operation)
                VALUES (gen_random_uuid(), :markerId, :principalType, :principalId, :operation)
                ON CONFLICT DO NOTHING
                """)
                .param(PARAM_MARKER_ID, markerId).param("principalType", principalType)
                .param("principalId", principalId).param(COL_OPERATION, operation)
                .update();
    }
}

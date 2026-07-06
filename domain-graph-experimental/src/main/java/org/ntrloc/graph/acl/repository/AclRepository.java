package org.ntrloc.graph.acl.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class AclRepository {

    // Sentinel used only to keep "IN (:groupIds)" valid SQL when a principal belongs to
    // no groups — a real uuidv7()-generated id can never collide with this value.
    private static final UUID NO_GROUPS_SENTINEL = new UUID(0L, 0L);

    public record UserRow(UUID id, String externalId, String displayName) {}

    public record GroupRow(UUID id, String name) {}

    public record MarkerRow(UUID id, String name, String description) {}

    private final JdbcClient jdbcClient;

    public AclRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // --- Principal resolution reads ---

    public Optional<UserRow> findUserByExternalId(String externalId) {
        return jdbcClient.sql("SELECT id, external_id, display_name FROM acl_user WHERE external_id = :externalId")
                .param("externalId", externalId)
                .query((rs, n) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("external_id"),
                        rs.getString("display_name")))
                .optional();
    }

    public Set<UUID> getGroupIdsForUser(UUID userId) {
        return Set.copyOf(jdbcClient.sql("SELECT group_id FROM acl_group_member WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, n) -> rs.getObject("group_id", UUID.class))
                .list());
    }

    // --- Marker assignment reads ---

    public Map<UUID, List<UUID>> getMarkerIdsByItemType() {
        return jdbcClient.sql("SELECT item_type_id, marker_id FROM acl_item_type_marker")
                .query((rs, n) -> Map.entry(
                        rs.getObject("item_type_id", UUID.class),
                        rs.getObject("marker_id", UUID.class)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    // --- Grant reads ---

    public Set<UUID> getGrantedMarkerIds(UUID userId, Set<UUID> groupIds, String operation) {
        Set<UUID> safeGroupIds = groupIds.isEmpty() ? Set.of(NO_GROUPS_SENTINEL) : groupIds;
        return Set.copyOf(jdbcClient.sql("""
                SELECT DISTINCT marker_id FROM acl_grant
                WHERE operation = :operation
                  AND ((principal_type = 'USER'  AND principal_id = :userId)
                    OR (principal_type = 'GROUP' AND principal_id IN (:groupIds)))
                """)
                .param("operation", operation)
                .param("userId", userId)
                .param("groupIds", safeGroupIds)
                .query((rs, n) -> rs.getObject("marker_id", UUID.class))
                .list());
    }

    // --- Test-data seeding writes ---

    public UserRow createUser(String externalId, String displayName) {
        UUID id = jdbcClient.sql("INSERT INTO acl_user (external_id, display_name) VALUES (:externalId, :displayName) RETURNING id")
                .param("externalId", externalId).param("displayName", displayName)
                .query(UUID.class).single();
        return new UserRow(id, externalId, displayName);
    }

    public GroupRow createGroup(String name) {
        UUID id = jdbcClient.sql("INSERT INTO acl_group (name) VALUES (:name) RETURNING id")
                .param("name", name)
                .query(UUID.class).single();
        return new GroupRow(id, name);
    }

    public void addUserToGroup(UUID userId, UUID groupId) {
        jdbcClient.sql("INSERT INTO acl_group_member (user_id, group_id) VALUES (:userId, :groupId)")
                .param("userId", userId).param("groupId", groupId).update();
    }

    public MarkerRow createMarker(String name, String description) {
        UUID id = jdbcClient.sql("INSERT INTO acl_marker (name, description) VALUES (:name, :description) RETURNING id")
                .param("name", name).param("description", description)
                .query(UUID.class).single();
        return new MarkerRow(id, name, description);
    }

    public void assignMarkerToItemType(UUID itemTypeId, UUID markerId) {
        jdbcClient.sql("INSERT INTO acl_item_type_marker (item_type_id, marker_id) VALUES (:itemTypeId, :markerId)")
                .param("itemTypeId", itemTypeId).param("markerId", markerId).update();
    }

    public void grant(UUID markerId, String principalType, UUID principalId, String operation) {
        jdbcClient.sql("""
                INSERT INTO acl_grant (marker_id, principal_type, principal_id, operation)
                VALUES (:markerId, :principalType, :principalId, :operation)
                """)
                .param("markerId", markerId).param("principalType", principalType)
                .param("principalId", principalId).param("operation", operation)
                .update();
    }
}

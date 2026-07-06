package org.ntrloc.graph.db.partition.authorization.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class AuthorizationRepository {

    // Sentinel used only to keep "IN (:groupIds)" valid SQL when a principal belongs to
    // no groups — a real uuidv7()-generated id can never collide with this value.
    private static final UUID NO_GROUPS_SENTINEL = new UUID(0L, 0L);

    public record MarkerRow(UUID id, String name, String description) {}

    private final JdbcClient jdbcClient;

    public AuthorizationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // --- Marker assignment reads ---

    public Map<UUID, List<UUID>> getMarkerIdsByItemType() {
        return jdbcClient.sql("SELECT item_type_id, marker_id FROM authorization_item_type_marker")
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
                SELECT DISTINCT marker_id FROM authorization_grant
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

    public MarkerRow createMarker(String name, String description) {
        UUID id = jdbcClient.sql("INSERT INTO authorization_marker (name, description) VALUES (:name, :description) RETURNING id")
                .param("name", name).param("description", description)
                .query(UUID.class).single();
        return new MarkerRow(id, name, description);
    }

    public void assignMarkerToItemType(UUID itemTypeId, UUID markerId) {
        jdbcClient.sql("INSERT INTO authorization_item_type_marker (item_type_id, marker_id) VALUES (:itemTypeId, :markerId)")
                .param("itemTypeId", itemTypeId).param("markerId", markerId).update();
    }

    public void grant(UUID markerId, String principalType, UUID principalId, String operation) {
        jdbcClient.sql("""
                INSERT INTO authorization_grant (marker_id, principal_type, principal_id, operation)
                VALUES (:markerId, :principalType, :principalId, :operation)
                """)
                .param("markerId", markerId).param("principalType", principalType)
                .param("principalId", principalId).param("operation", operation)
                .update();
    }
}

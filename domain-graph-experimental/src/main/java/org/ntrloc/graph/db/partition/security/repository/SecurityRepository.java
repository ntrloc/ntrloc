package org.ntrloc.graph.db.partition.security.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class SecurityRepository {

    public record UserRow(UUID id, String externalId, String displayName) {}

    public record GroupRow(UUID id, String name) {}

    private final JdbcClient jdbcClient;

    public SecurityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // --- Principal resolution reads ---

    public Optional<UserRow> findUserByExternalId(String externalId) {
        return jdbcClient.sql("SELECT id, external_id, display_name FROM security_user WHERE external_id = :externalId")
                .param("externalId", externalId)
                .query((rs, n) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("external_id"),
                        rs.getString("display_name")))
                .optional();
    }

    public Set<UUID> getGroupIdsForUser(UUID userId) {
        return Set.copyOf(jdbcClient.sql("SELECT group_id FROM security_group_member WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, n) -> rs.getObject("group_id", UUID.class))
                .list());
    }

    // --- Test-data seeding writes ---

    public UserRow createUser(String externalId, String displayName) {
        UUID id = jdbcClient.sql("INSERT INTO security_user (external_id, display_name) VALUES (:externalId, :displayName) RETURNING id")
                .param("externalId", externalId).param("displayName", displayName)
                .query(UUID.class).single();
        return new UserRow(id, externalId, displayName);
    }

    public GroupRow createGroup(String name) {
        UUID id = jdbcClient.sql("INSERT INTO security_group (name) VALUES (:name) RETURNING id")
                .param("name", name)
                .query(UUID.class).single();
        return new GroupRow(id, name);
    }

    public void addUserToGroup(UUID userId, UUID groupId) {
        jdbcClient.sql("INSERT INTO security_group_member (user_id, group_id) VALUES (:userId, :groupId)")
                .param("userId", userId).param("groupId", groupId).update();
    }
}

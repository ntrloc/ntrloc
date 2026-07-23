package org.ntrloc.graph.db.partition.security.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class SecurityRepository {

    public record UserRow(UUID id, String externalId, String displayName, boolean isSuperuser) {}

    public record GroupRow(UUID id, String name) {}

    public record LocalCredentialsRow(UUID userId, String email, String passwordHash, String role, boolean active) {}

    public record PersonalAccessTokenRow(UUID id, UUID userId, String name, OffsetDateTime createdAt, OffsetDateTime expiresAt) {}

    private final JdbcClient jdbcClient;

    public SecurityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // --- Principal resolution reads ---

    public Optional<UserRow> findUserByExternalId(String externalId) {
        return jdbcClient.sql("SELECT id, external_id, display_name, is_superuser FROM security_user WHERE external_id = :externalId")
                .param("externalId", externalId)
                .query((rs, n) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("external_id"),
                        rs.getString("display_name"),
                        rs.getBoolean("is_superuser")))
                .optional();
    }

    // Used by UserAdminController to populate assignee pickers (e.g. a User Task's assignee
    // select in the process editor) -- not part of principal resolution, just a plain listing.
    public List<UserRow> listUsers() {
        return jdbcClient.sql("SELECT id, external_id, display_name, is_superuser FROM security_user ORDER BY display_name")
                .query((rs, n) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("external_id"),
                        rs.getString("display_name"),
                        rs.getBoolean("is_superuser")))
                .list();
    }

    public Set<UUID> getGroupIdsForUser(UUID userId) {
        return Set.copyOf(jdbcClient.sql("SELECT group_id FROM security_group_member WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, n) -> rs.getObject("group_id", UUID.class))
                .list());
    }

    // --- Test-data seeding writes ---

    public UserRow createUser(String externalId, String displayName, boolean isSuperuser) {
        UUID id = jdbcClient.sql("""
                INSERT INTO security_user (external_id, display_name, is_superuser)
                VALUES (:externalId, :displayName, :isSuperuser) RETURNING id
                """)
                .param("externalId", externalId).param("displayName", displayName).param("isSuperuser", isSuperuser)
                .query(UUID.class).single();
        return new UserRow(id, externalId, displayName, isSuperuser);
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

    // --- Local credentials ---

    public void createLocalCredentials(UUID userId, String email, String passwordHash, String role) {
        jdbcClient.sql("""
                INSERT INTO security_local_credentials (user_id, email, password_hash, role)
                VALUES (:userId, :email, :passwordHash, :role)
                """)
                .param("userId", userId).param("email", email)
                .param("passwordHash", passwordHash).param("role", role)
                .update();
    }

    public Optional<LocalCredentialsRow> findCredentialsByEmail(String email) {
        return jdbcClient.sql("""
                SELECT user_id, email, password_hash, role, active
                FROM security_local_credentials WHERE email = :email
                """)
                .param("email", email)
                .query((rs, n) -> new LocalCredentialsRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("role"),
                        rs.getBoolean("active")))
                .optional();
    }

    // --- Personal access tokens ---

    public UUID createPersonalAccessToken(UUID userId, String tokenHash, String name, OffsetDateTime expiresAt) {
        return jdbcClient.sql("""
                INSERT INTO security_personal_access_token (user_id, token_hash, name, expires_at)
                VALUES (:userId, :tokenHash, :name, :expiresAt) RETURNING id
                """)
                .param("userId", userId).param("tokenHash", tokenHash)
                .param("name", name).param("expiresAt", expiresAt)
                .query(UUID.class).single();
    }

    public Optional<UserRow> findUserByValidTokenHash(String tokenHash) {
        return jdbcClient.sql("""
                SELECT u.id, u.external_id, u.display_name, u.is_superuser
                FROM security_user u
                JOIN security_personal_access_token t ON t.user_id = u.id
                WHERE t.token_hash = :tokenHash
                  AND (t.expires_at IS NULL OR t.expires_at > NOW())
                """)
                .param("tokenHash", tokenHash)
                .query((rs, n) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("external_id"),
                        rs.getString("display_name"),
                        rs.getBoolean("is_superuser")))
                .optional();
    }

    public List<PersonalAccessTokenRow> listTokensForUser(UUID userId) {
        return jdbcClient.sql("""
                SELECT id, user_id, name, created_at, expires_at
                FROM security_personal_access_token WHERE user_id = :userId
                ORDER BY created_at DESC
                """)
                .param("userId", userId)
                .query((rs, n) -> new PersonalAccessTokenRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .list();
    }

    public void revokeToken(UUID tokenId, UUID userId) {
        jdbcClient.sql("DELETE FROM security_personal_access_token WHERE id = :tokenId AND user_id = :userId")
                .param("tokenId", tokenId).param("userId", userId)
                .update();
    }
}

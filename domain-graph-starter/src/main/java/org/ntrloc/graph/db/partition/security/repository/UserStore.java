package org.ntrloc.graph.db.partition.security.repository;

import org.ntrloc.graph.db.partition.changelog.ChangeLog;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Users, their local credentials and their personal access tokens. Groups and memberships are
// UserGroupStore's. Writes go through UserService (ArchUnit), which gives them a transaction for the
// changelog entries they append.
@Component
public class UserStore {

    private static final String COL_EMAIL = "email";
    private static final String PARAM_USER_ID = "userId";

    public record UserRow(UUID id, String displayName, String email, boolean isAdmin) {}

    public record LocalCredentialsRow(UUID userId, String passwordHash, boolean active) {}

    public record PersonalAccessTokenRow(UUID id, UUID userId, String name, OffsetDateTime createdAt, OffsetDateTime expiresAt) {}

    static final RowMapper<UserRow> USER_ROW = (rs, _) -> new UserRow(
            rs.getObject("id", UUID.class),
            rs.getString("display_name"),
            rs.getString(COL_EMAIL),
            rs.getBoolean("is_admin"));

    private final JdbcClient jdbcClient;
    private final ChangeLog changeLog;
    private final UserAccessCache accessCache;

    public UserStore(JdbcClient jdbcClient, ChangeLog changeLog, UserAccessCache accessCache) {
        this.jdbcClient = jdbcClient;
        this.changeLog = changeLog;
        this.accessCache = accessCache;
    }

    // --- Principal resolution reads ---

    // Case-insensitive, matching the unique index on lower(email).
    public Optional<UserRow> findUserByEmail(String email) {
        return jdbcClient.sql("SELECT id, display_name, email, is_admin FROM security_user WHERE lower(email) = lower(:email)")
                .param(COL_EMAIL, email)
                .query(USER_ROW)
                .optional();
    }

    // What every stored user reference (ledger attribution, task assignee, process variable)
    // resolves against -- never the email, which can change over a user's lifetime.
    public Optional<UserRow> findUserById(UUID id) {
        return jdbcClient.sql("SELECT id, display_name, email, is_admin FROM security_user WHERE id = :userId")
                .param(PARAM_USER_ID, id)
                .query(USER_ROW)
                .optional();
    }

    // Batched id lookup -- what RegisterStore resolves a page's worth of
    // createdBy/updatedBy UUIDs against in one round trip (UserInfo), rather than one
    // findUserById call per row. Empty input short-circuits rather than issuing "IN ()", which
    // Postgres rejects as a syntax error.
    public List<UserRow> findUsersByIds(Set<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbcClient.sql("SELECT id, display_name, email, is_admin FROM security_user WHERE id IN (:ids)")
                .param("ids", ids)
                .query(USER_ROW)
                .list();
    }

    // Used by UserAdminController to populate assignee pickers (e.g. a User Task's assignee
    // select in the process editor) -- not part of principal resolution, just a plain listing.
    public List<UserRow> listUsers() {
        return jdbcClient.sql("SELECT id, display_name, email, is_admin FROM security_user ORDER BY display_name")
                .query(USER_ROW)
                .list();
    }

    // --- Writes. Each appends its changelog entry (docs/ntrloc-system-model-notes.md §13.6), in the
    // caller's transaction (UserService). ---

    public UserRow createUser(String displayName, String email, boolean isAdmin) {
        UUID id = jdbcClient.sql("""
                INSERT INTO security_user (display_name, email, is_admin)
                VALUES (:displayName, :email, :isAdmin) RETURNING id
                """)
                .param("displayName", displayName)
                .param(COL_EMAIL, email).param("isAdmin", isAdmin)
                .query(UUID.class).single();
        changeLog.append("user.created", "user:" + id, List.of(id), null,
                Map.of("displayName", displayName, "email", email, "isAdmin", isAdmin));
        return new UserRow(id, displayName, email, isAdmin);
    }

    // --- Local credentials ---

    public void createLocalCredentials(UUID userId, String passwordHash) {
        jdbcClient.sql("""
                INSERT INTO security_local_credentials (user_id, password_hash)
                VALUES (:userId, :passwordHash)
                """)
                .param(PARAM_USER_ID, userId)
                .param("passwordHash", passwordHash)
                .update();
    }

    public void updatePasswordHash(UUID userId, String newPasswordHash) {
        jdbcClient.sql("UPDATE security_local_credentials SET password_hash = :hash WHERE user_id = :userId")
                .param("hash", newPasswordHash)
                .param(PARAM_USER_ID, userId)
                .update();
    }

    public void updateUser(UUID userId, String displayName, String email, boolean isAdmin) {
        accessCache.changed();
        UserRow old = findUserById(userId).orElseThrow();
        jdbcClient.sql("UPDATE security_user SET display_name = :displayName, email = :email, is_admin = :isAdmin WHERE id = :userId")
                .param("displayName", displayName)
                .param(COL_EMAIL, email)
                .param("isAdmin", isAdmin)
                .param(PARAM_USER_ID, userId)
                .update();
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (!old.displayName().equals(displayName)) { before.put("displayName", old.displayName()); after.put("displayName", displayName); }
        if (!old.email().equals(email)) { before.put("email", old.email()); after.put("email", email); }
        if (old.isAdmin() != isAdmin) { before.put("isAdmin", old.isAdmin()); after.put("isAdmin", isAdmin); }
        if (!after.isEmpty()) {
            after.put("displayName", displayName);
            changeLog.append("user.updated", "user:" + userId, List.of(userId), before, after);
        }
    }

    // Cascades to memberships, credentials, tokens and process_group_member. Grant tables refer to
    // principals by a bare UUID, so the user's grants, and the changelog entry, are the caller's
    // (UserService).
    public void deleteUser(UUID userId) {
        accessCache.changed();
        jdbcClient.sql("DELETE FROM security_user WHERE id = :userId")
                .param(PARAM_USER_ID, userId)
                .update();
    }

    public Optional<LocalCredentialsRow> findCredentialsByUserId(UUID userId) {
        return jdbcClient.sql("""
                SELECT user_id, password_hash, active
                FROM security_local_credentials WHERE user_id = :userId
                """)
                .param(PARAM_USER_ID, userId)
                .query((rs, _) -> new LocalCredentialsRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("password_hash"),
                        rs.getBoolean("active")))
                .optional();
    }

    // --- Personal access tokens ---

    public UUID createPersonalAccessToken(UUID userId, String tokenHash, String name, OffsetDateTime expiresAt) {
        return jdbcClient.sql("""
                INSERT INTO security_personal_access_token (user_id, token_hash, name, expires_at)
                VALUES (:userId, :tokenHash, :name, :expiresAt) RETURNING id
                """)
                .param(PARAM_USER_ID, userId).param("tokenHash", tokenHash)
                .param("name", name).param("expiresAt", expiresAt)
                .query(UUID.class).single();
    }

    public Optional<UserRow> findUserByValidTokenHash(String tokenHash) {
        return jdbcClient.sql("""
                SELECT u.id, u.display_name, u.email, u.is_admin
                FROM security_user u
                JOIN security_personal_access_token t ON t.user_id = u.id
                WHERE t.token_hash = :tokenHash
                  AND (t.expires_at IS NULL OR t.expires_at > NOW())
                """)
                .param("tokenHash", tokenHash)
                .query(USER_ROW)
                .optional();
    }

    public List<PersonalAccessTokenRow> listTokensForUser(UUID userId) {
        return jdbcClient.sql("""
                SELECT id, user_id, name, created_at, expires_at
                FROM security_personal_access_token WHERE user_id = :userId
                ORDER BY created_at DESC
                """)
                .param(PARAM_USER_ID, userId)
                .query((rs, _) -> new PersonalAccessTokenRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .list();
    }

    public void revokeToken(UUID tokenId, UUID userId) {
        jdbcClient.sql("DELETE FROM security_personal_access_token WHERE id = :tokenId AND user_id = :userId")
                .param("tokenId", tokenId).param(PARAM_USER_ID, userId)
                .update();
    }
}

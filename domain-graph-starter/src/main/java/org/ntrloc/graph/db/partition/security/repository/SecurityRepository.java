package org.ntrloc.graph.db.partition.security.repository;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class SecurityRepository {

    private static final String COL_EMAIL = "email";
    private static final String PARAM_USER_ID = "userId";
    private static final String PARAM_GROUP_ID = "groupId";

    public record UserRow(UUID id, String displayName, String email, boolean isSuperuser) {}

    public record UserGroupRow(UUID id, String name) {}

    public record LocalCredentialsRow(UUID userId, String passwordHash, String role, boolean active) {}

    public record PersonalAccessTokenRow(UUID id, UUID userId, String name, OffsetDateTime createdAt, OffsetDateTime expiresAt) {}

    private static final RowMapper<UserRow> USER_ROW = (rs, n) -> new UserRow(
            rs.getObject("id", UUID.class),
            rs.getString("display_name"),
            rs.getString(COL_EMAIL),
            rs.getBoolean("is_superuser"));

    private final JdbcClient jdbcClient;

    public SecurityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // --- Principal resolution reads ---

    // Case-insensitive, matching the unique index on lower(email).
    public Optional<UserRow> findUserByEmail(String email) {
        return jdbcClient.sql("SELECT id, display_name, email, is_superuser FROM security_user WHERE lower(email) = lower(:email)")
                .param(COL_EMAIL, email)
                .query(USER_ROW)
                .optional();
    }

    // What every stored user reference (ledger attribution, task assignee, process variable)
    // resolves against -- never the email, which can change over a user's lifetime.
    public Optional<UserRow> findUserById(UUID id) {
        return jdbcClient.sql("SELECT id, display_name, email, is_superuser FROM security_user WHERE id = :userId")
                .param(PARAM_USER_ID, id)
                .query(USER_ROW)
                .optional();
    }

    // Batched id lookup -- what RegisterPartitionManager resolves a page's worth of
    // createdBy/updatedBy UUIDs against in one round trip (UserInfo), rather than one
    // findUserById call per row. Empty input short-circuits rather than issuing "IN ()", which
    // Postgres rejects as a syntax error.
    public List<UserRow> findUsersByIds(Set<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbcClient.sql("SELECT id, display_name, email, is_superuser FROM security_user WHERE id IN (:ids)")
                .param("ids", ids)
                .query(USER_ROW)
                .list();
    }

    // Used by UserAdminController to populate assignee pickers (e.g. a User Task's assignee
    // select in the process editor) -- not part of principal resolution, just a plain listing.
    public List<UserRow> listUsers() {
        return jdbcClient.sql("SELECT id, display_name, email, is_superuser FROM security_user ORDER BY display_name")
                .query(USER_ROW)
                .list();
    }

    // Transitive: a user's effective groups are their direct groups, plus every group any of those
    // groups is itself (transitively) a member of via security_user_group_member_group. Everything
    // downstream (AuthorizationCacheManager.effectiveSet/effectiveNestedSet, RequestPermissionContext)
    // already unions grants over whatever this returns, so nothing above this method needs to know
    // nesting exists at all -- the closure has to be complete by the time it leaves here.
    public Set<UUID> getUserGroupIdsForUser(UUID userId) {
        return Set.copyOf(jdbcClient.sql("""
                WITH RECURSIVE effective_groups(group_id) AS (
                    SELECT group_id FROM security_user_group_member WHERE user_id = :userId
                    UNION
                    SELECT smg.group_id
                    FROM security_user_group_member_group smg
                    JOIN effective_groups eg ON smg.member_group_id = eg.group_id
                )
                SELECT group_id FROM effective_groups
                """)
                .param(PARAM_USER_ID, userId)
                .query((rs, n) -> rs.getObject("group_id", UUID.class))
                .list());
    }

    public List<UserGroupRow> getUserGroupsForUser(UUID userId) {
        return jdbcClient.sql("""
                SELECT g.id, g.name FROM security_user_group g
                JOIN security_user_group_member gm ON gm.group_id = g.id
                WHERE gm.user_id = :userId ORDER BY g.name
                """)
                .param(PARAM_USER_ID, userId)
                .query((rs, n) -> new UserGroupRow(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    // --- Test-data seeding writes ---

    public UserRow createUser(String displayName, String email, boolean isSuperuser) {
        UUID id = jdbcClient.sql("""
                INSERT INTO security_user (display_name, email, is_superuser)
                VALUES (:displayName, :email, :isSuperuser) RETURNING id
                """)
                .param("displayName", displayName)
                .param(COL_EMAIL, email).param("isSuperuser", isSuperuser)
                .query(UUID.class).single();
        return new UserRow(id, displayName, email, isSuperuser);
    }

    public UserGroupRow createUserGroup(String name) {
        UUID id = jdbcClient.sql("INSERT INTO security_user_group (name) VALUES (:name) RETURNING id")
                .param("name", name)
                .query(UUID.class).single();
        return new UserGroupRow(id, name);
    }

    public void addUserToUserGroup(UUID userId, UUID groupId) {
        jdbcClient.sql("INSERT INTO security_user_group_member (user_id, group_id) VALUES (:userId, :groupId) ON CONFLICT DO NOTHING")
                .param(PARAM_USER_ID, userId).param(PARAM_GROUP_ID, groupId).update();
    }

    // memberUserGroupId becomes a member of groupId (same direction as addUserToUserGroup: member, then
    // container). Self-membership is caught by the table's own CHECK; a deeper cycle (groupId
    // already transitively a member of memberUserGroupId) has no equivalent constraint, so it's checked
    // here before the insert.
    public void addUserGroupToUserGroup(UUID memberUserGroupId, UUID groupId) {
        if (memberUserGroupId.equals(groupId)) {
            throw new IllegalArgumentException("A group cannot be a member of itself");
        }
        if (isTransitiveMemberOf(groupId, memberUserGroupId)) {
            throw new IllegalArgumentException("Adding this membership would create a cycle");
        }
        jdbcClient.sql("INSERT INTO security_user_group_member_group (member_group_id, group_id) VALUES (:memberUserGroupId, :groupId) ON CONFLICT DO NOTHING")
                .param("memberUserGroupId", memberUserGroupId).param(PARAM_GROUP_ID, groupId).update();
    }

    // True iff candidateMemberId is already (directly or transitively) a member of ofUserGroupId --
    // i.e. whether groupId already appears somewhere in candidateMemberId's own membership chain.
    // Used as the cycle guard for addUserGroupToUserGroup: adding "ofUserGroupId member of candidateMemberId"
    // when this already holds true would close a loop.
    private boolean isTransitiveMemberOf(UUID candidateMemberId, UUID ofUserGroupId) {
        return Boolean.TRUE.equals(jdbcClient.sql("""
                WITH RECURSIVE ancestors(group_id) AS (
                    SELECT group_id FROM security_user_group_member_group WHERE member_group_id = :candidateMemberId
                    UNION
                    SELECT smg.group_id
                    FROM security_user_group_member_group smg
                    JOIN ancestors a ON smg.member_group_id = a.group_id
                )
                SELECT EXISTS (SELECT 1 FROM ancestors WHERE group_id = :ofUserGroupId)
                """)
                .param("candidateMemberId", candidateMemberId).param("ofUserGroupId", ofUserGroupId)
                .query(Boolean.class).single());
    }

    public void removeUserGroupFromUserGroup(UUID memberUserGroupId, UUID groupId) {
        jdbcClient.sql("DELETE FROM security_user_group_member_group WHERE member_group_id = :memberUserGroupId AND group_id = :groupId")
                .param("memberUserGroupId", memberUserGroupId).param(PARAM_GROUP_ID, groupId).update();
    }

    // Direct (non-transitive) child groups -- groups that are themselves members of groupId.
    public List<UserGroupRow> listMemberUserGroups(UUID groupId) {
        return jdbcClient.sql("""
                SELECT g.id, g.name FROM security_user_group g
                JOIN security_user_group_member_group smg ON smg.member_group_id = g.id
                WHERE smg.group_id = :groupId ORDER BY g.name
                """)
                .param(PARAM_GROUP_ID, groupId)
                .query((rs, n) -> new UserGroupRow(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    // Direct (non-transitive) parent groups -- groups that groupId is itself a member of.
    public List<UserGroupRow> listContainingUserGroups(UUID groupId) {
        return jdbcClient.sql("""
                SELECT g.id, g.name FROM security_user_group g
                JOIN security_user_group_member_group smg ON smg.group_id = g.id
                WHERE smg.member_group_id = :groupId ORDER BY g.name
                """)
                .param(PARAM_GROUP_ID, groupId)
                .query((rs, n) -> new UserGroupRow(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    public List<UserGroupRow> listUserGroups() {
        return jdbcClient.sql("SELECT id, name FROM security_user_group ORDER BY name")
                .query((rs, n) -> new UserGroupRow(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    public Optional<UserGroupRow> findUserGroupById(UUID groupId) {
        return jdbcClient.sql("SELECT id, name FROM security_user_group WHERE id = :id")
                .param("id", groupId)
                .query((rs, n) -> new UserGroupRow(rs.getObject("id", UUID.class), rs.getString("name")))
                .optional();
    }

    public UserGroupRow updateUserGroup(UUID groupId, String name) {
        jdbcClient.sql("UPDATE security_user_group SET name = :name WHERE id = :id")
                .param("name", name).param("id", groupId).update();
        return new UserGroupRow(groupId, name);
    }

    public void deleteUserGroup(UUID groupId) {
        jdbcClient.sql("DELETE FROM security_user_group WHERE id = :id")
                .param("id", groupId).update();
    }

    public List<UserRow> listUserGroupMembers(UUID groupId) {
        return jdbcClient.sql("""
                SELECT u.id, u.display_name, u.email, u.is_superuser
                FROM security_user u
                JOIN security_user_group_member gm ON gm.user_id = u.id
                WHERE gm.group_id = :groupId
                ORDER BY u.display_name
                """)
                .param(PARAM_GROUP_ID, groupId)
                .query(USER_ROW)
                .list();
    }

    public void removeUserFromUserGroup(UUID userId, UUID groupId) {
        jdbcClient.sql("DELETE FROM security_user_group_member WHERE user_id = :userId AND group_id = :groupId")
                .param(PARAM_USER_ID, userId).param(PARAM_GROUP_ID, groupId).update();
    }

    public Optional<UserGroupRow> findUserGroupByName(String name) {
        return jdbcClient.sql("SELECT id, name FROM security_user_group WHERE name = :name")
                .param("name", name)
                .query((rs, n) -> new UserGroupRow(rs.getObject("id", UUID.class), rs.getString("name")))
                .optional();
    }

    // --- Local credentials ---

    public void createLocalCredentials(UUID userId, String passwordHash, String role) {
        jdbcClient.sql("""
                INSERT INTO security_local_credentials (user_id, password_hash, role)
                VALUES (:userId, :passwordHash, :role)
                """)
                .param(PARAM_USER_ID, userId)
                .param("passwordHash", passwordHash).param("role", role)
                .update();
    }

    public void updatePasswordHash(UUID userId, String newPasswordHash) {
        jdbcClient.sql("UPDATE security_local_credentials SET password_hash = :hash WHERE user_id = :userId")
                .param("hash", newPasswordHash)
                .param(PARAM_USER_ID, userId)
                .update();
    }

    public void updateUser(UUID userId, String displayName, String email, boolean isSuperuser) {
        jdbcClient.sql("UPDATE security_user SET display_name = :displayName, email = :email, is_superuser = :isSuperuser WHERE id = :userId")
                .param("displayName", displayName)
                .param(COL_EMAIL, email)
                .param("isSuperuser", isSuperuser)
                .param(PARAM_USER_ID, userId)
                .update();
    }

    public void updateLocalCredentialsRole(UUID userId, String role) {
        jdbcClient.sql("UPDATE security_local_credentials SET role = :role WHERE user_id = :userId")
                .param("role", role)
                .param(PARAM_USER_ID, userId)
                .update();
    }

    // Cascades to security_user_group_member, security_local_credentials, security_personal_access_token,
    // and process_group_member (all ON DELETE CASCADE) -- nothing else references security_user via
    // a real FK, since authorization grant tables key principals by a plain UUID (GROUP or USER)
    // rather than an FK to either table.
    public void deleteUser(UUID userId) {
        jdbcClient.sql("DELETE FROM security_user WHERE id = :userId")
                .param(PARAM_USER_ID, userId)
                .update();
    }

    public Optional<LocalCredentialsRow> findCredentialsByUserId(UUID userId) {
        return jdbcClient.sql("""
                SELECT user_id, password_hash, role, active
                FROM security_local_credentials WHERE user_id = :userId
                """)
                .param(PARAM_USER_ID, userId)
                .query((rs, n) -> new LocalCredentialsRow(
                        rs.getObject("user_id", UUID.class),
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
                .param(PARAM_USER_ID, userId).param("tokenHash", tokenHash)
                .param("name", name).param("expiresAt", expiresAt)
                .query(UUID.class).single();
    }

    public Optional<UserRow> findUserByValidTokenHash(String tokenHash) {
        return jdbcClient.sql("""
                SELECT u.id, u.display_name, u.email, u.is_superuser
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
                .param("tokenId", tokenId).param(PARAM_USER_ID, userId)
                .update();
    }
}

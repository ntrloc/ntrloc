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

@Component
public class UserStore {

    private static final String COL_EMAIL = "email";
    private static final String PARAM_USER_ID = "userId";
    private static final String PARAM_GROUP_ID = "groupId";

    public record UserRow(UUID id, String displayName, String email, boolean isAdmin) {}

    public record UserGroupRow(UUID id, String name, UUID parentId) {}

    public record LocalCredentialsRow(UUID userId, String passwordHash, boolean active) {}

    public record PersonalAccessTokenRow(UUID id, UUID userId, String name, OffsetDateTime createdAt, OffsetDateTime expiresAt) {}

    private static final RowMapper<UserRow> USER_ROW = (rs, _) -> new UserRow(
            rs.getObject("id", UUID.class),
            rs.getString("display_name"),
            rs.getString(COL_EMAIL),
            rs.getBoolean("is_admin"));

    private static final String SELECT_GROUPS = "SELECT g.id, g.name, g.parent_id FROM security_user_group g";

    private static final RowMapper<UserGroupRow> GROUP_ROW = (rs, _) -> new UserGroupRow(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("parent_id", UUID.class));

    private final JdbcClient jdbcClient;
    private final ChangeLog changeLog;

    public UserStore(JdbcClient jdbcClient, ChangeLog changeLog) {
        this.jdbcClient = jdbcClient;
        this.changeLog = changeLog;
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

    // A user's effective groups: the groups they're in, and every group above each of those. Everything
    // downstream (grant lookups, UserGrantsView) unions grants over what this returns, so the closure has
    // to be complete here.
    public Set<UUID> getUserGroupIdsForUser(UUID userId) {
        return Set.copyOf(jdbcClient.sql("""
                WITH RECURSIVE effective_groups(group_id) AS (
                    SELECT group_id FROM security_user_group_member WHERE user_id = :userId
                    UNION
                    SELECT g.parent_id FROM security_user_group g
                    JOIN effective_groups eg ON g.id = eg.group_id
                    WHERE g.parent_id IS NOT NULL
                )
                SELECT group_id FROM effective_groups
                """)
                .param(PARAM_USER_ID, userId)
                .query((rs, _) -> rs.getObject("group_id", UUID.class))
                .list());
    }

    public List<UserGroupRow> getUserGroupsForUser(UUID userId) {
        return jdbcClient.sql(SELECT_GROUPS + " JOIN security_user_group_member gm ON gm.group_id = g.id WHERE gm.user_id = :userId ORDER BY g.name")
                .param(PARAM_USER_ID, userId)
                .query(GROUP_ROW)
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

    // The root group, "everyone" (DefaultUserGroupInitializer); the database allows only one group without a
    // parent.
    public UserGroupRow createRootUserGroup(String name) {
        UUID id = jdbcClient.sql("INSERT INTO security_user_group (name) VALUES (:name) RETURNING id")
                .param("name", name).query(UUID.class).single();
        changeLog.append("group.created", "group:" + id, List.of(id), null, Map.of("name", name));
        return new UserGroupRow(id, name, null);
    }

    // A group directly under the root.
    public UserGroupRow createUserGroup(String name) {
        return createUserGroup(name, jdbcClient.sql("SELECT id FROM security_user_group WHERE parent_id IS NULL").query(UUID.class).single());
    }

    public UserGroupRow createUserGroup(String name, UUID parentId) {
        UUID id = jdbcClient.sql("INSERT INTO security_user_group (name, parent_id) VALUES (:name, :parentId) RETURNING id")
                .param("name", name)
                .param("parentId", java.util.Objects.requireNonNull(parentId, "every group but the root has a parent"))
                .query(UUID.class).single();
        changeLog.append("group.created", "group:" + id, List.of(id, parentId), null,
                Map.of("name", name, "parentId", parentId, "parentName", groupName(parentId)));
        return new UserGroupRow(id, name, parentId);
    }

    // Moves the group under newParentId. Refused if that would put the group under itself.
    public void setParent(UUID groupId, UUID newParentId) {
        if (groupId.equals(newParentId) || ancestorsOf(newParentId).contains(groupId)) {
            throw new IllegalArgumentException("A group can't be moved under itself or one of its own subgroups");
        }
        UserGroupRow group = findUserGroupById(groupId).orElseThrow();
        UUID oldParentId = group.parentId();
        if (newParentId.equals(oldParentId)) return;
        jdbcClient.sql("UPDATE security_user_group SET parent_id = :parentId WHERE id = :id")
                .param("parentId", newParentId).param("id", groupId).update();
        var changed = new ArrayList<UUID>(List.of(groupId, newParentId));
        if (oldParentId != null) changed.add(oldParentId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("parentId", oldParentId);
        before.put("parentName", oldParentId == null ? null : groupName(oldParentId));
        changeLog.append("group.moved", "group:" + groupId, changed, before,
                Map.of("name", group.name(), "parentId", newParentId, "parentName", groupName(newParentId)));
    }

    public UserGroupRow updateUserGroup(UUID groupId, String name) {
        String oldName = groupName(groupId);
        jdbcClient.sql("UPDATE security_user_group SET name = :name WHERE id = :id")
                .param("name", name).param("id", groupId).update();
        if (!name.equals(oldName)) {
            changeLog.append("group.renamed", "group:" + groupId, List.of(groupId), Map.of("name", oldName), Map.of("name", name));
        }
        return findUserGroupById(groupId).orElseThrow();
    }

    /**
     * Deletes the group and, only with force, every group below it; returns the ids deleted. Without force,
     * a group with subgroups is refused (HasSubgroupsException). The deleted groups' grants, and the
     * changelog entries, are the caller's (UserService).
     */
    public List<UUID> deleteUserGroup(UUID groupId, boolean force) {
        List<UUID> subtree = subtreeOf(groupId);
        if (subtree.size() > 1 && !force) throw new HasSubgroupsException(subtree.size() - 1);
        jdbcClient.sql("DELETE FROM security_user_group WHERE id IN (:ids)").param("ids", subtree).update();
        return subtree;
    }

    /** A group with subgroups can only be deleted with force. */
    public static class HasSubgroupsException extends RuntimeException {
        public HasSubgroupsException(int subgroups) {
            super("This group has " + subgroups + (subgroups == 1 ? " subgroup" : " subgroups") + "; deleting it needs force=true, which deletes them too");
        }
    }

    public void addUserToUserGroup(UUID userId, UUID groupId) {
        int added = jdbcClient.sql("INSERT INTO security_user_group_member (user_id, group_id) VALUES (:userId, :groupId) ON CONFLICT DO NOTHING")
                .param(PARAM_USER_ID, userId).param(PARAM_GROUP_ID, groupId).update();
        if (added > 0) {
            logMembership("membership.added", userId, groupId);
        }
    }

    public void removeUserFromUserGroup(UUID userId, UUID groupId) {
        int removed = jdbcClient.sql("DELETE FROM security_user_group_member WHERE user_id = :userId AND group_id = :groupId")
                .param(PARAM_USER_ID, userId).param(PARAM_GROUP_ID, groupId).update();
        if (removed > 0) {
            logMembership("membership.removed", userId, groupId);
        }
    }

    private void logMembership(String kind, UUID userId, UUID groupId) {
        Map<String, Object> membership = Map.of("userId", userId, "userName", userName(userId), "groupId", groupId, "groupName", groupName(groupId));
        changeLog.append(kind, "membership:" + groupId + ":" + userId, List.of(userId, groupId),
                kind.endsWith("removed") ? membership : null, kind.endsWith("added") ? membership : null);
    }

    private String groupName(UUID groupId) {
        return findUserGroupById(groupId).map(UserGroupRow::name).orElse(null);
    }

    private String userName(UUID userId) {
        return findUserById(userId).map(UserRow::displayName).orElse(null);
    }

    // Every user not yet in the group joins it (DefaultUserGroupInitializer's backfill into "everyone").
    public void addAllUsersToUserGroup(UUID groupId) {
        List<UUID> added = jdbcClient.sql("""
                INSERT INTO security_user_group_member (user_id, group_id)
                SELECT id, :groupId FROM security_user
                WHERE id NOT IN (SELECT user_id FROM security_user_group_member WHERE group_id = :groupId)
                RETURNING user_id
                """)
                .param(PARAM_GROUP_ID, groupId).query(UUID.class).list();
        if (!added.isEmpty()) {
            added.forEach(userId -> logMembership("membership.added", userId, groupId));
        }
    }

    // --- Group reads ---

    // The group and every group below it, the group first.
    public List<UUID> subtreeOf(UUID groupId) {
        return jdbcClient.sql("""
                WITH RECURSIVE subtree(id, depth) AS (
                    SELECT id, 0 FROM security_user_group WHERE id = :groupId
                    UNION ALL
                    SELECT g.id, s.depth + 1 FROM security_user_group g JOIN subtree s ON g.parent_id = s.id
                )
                SELECT id FROM subtree ORDER BY depth
                """)
                .param(PARAM_GROUP_ID, groupId).query(UUID.class).list();
    }

    // Every group above this one, nearest first.
    public List<UUID> ancestorsOf(UUID groupId) {
        return jdbcClient.sql("""
                WITH RECURSIVE ancestors(id, depth) AS (
                    SELECT parent_id, 1 FROM security_user_group WHERE id = :groupId AND parent_id IS NOT NULL
                    UNION ALL
                    SELECT g.parent_id, a.depth + 1 FROM security_user_group g JOIN ancestors a ON g.id = a.id WHERE g.parent_id IS NOT NULL
                )
                SELECT id FROM ancestors ORDER BY depth
                """)
                .param(PARAM_GROUP_ID, groupId).query(UUID.class).list();
    }

    // Direct subgroups.
    public List<UserGroupRow> listMemberUserGroups(UUID groupId) {
        return jdbcClient.sql(SELECT_GROUPS + " WHERE g.parent_id = :groupId ORDER BY g.name")
                .param(PARAM_GROUP_ID, groupId)
                .query(GROUP_ROW)
                .list();
    }

    public List<UserGroupRow> listUserGroups() {
        return jdbcClient.sql(SELECT_GROUPS + " ORDER BY g.name").query(GROUP_ROW).list();
    }

    public Optional<UserGroupRow> findUserGroupById(UUID groupId) {
        return jdbcClient.sql(SELECT_GROUPS + " WHERE g.id = :id")
                .param("id", groupId)
                .query(GROUP_ROW)
                .optional();
    }

    public List<UserRow> listUserGroupMembers(UUID groupId) {
        return jdbcClient.sql("""
                SELECT u.id, u.display_name, u.email, u.is_admin
                FROM security_user u
                JOIN security_user_group_member gm ON gm.user_id = u.id
                WHERE gm.group_id = :groupId
                ORDER BY u.display_name
                """)
                .param(PARAM_GROUP_ID, groupId)
                .query(USER_ROW)
                .list();
    }

    public Optional<UserGroupRow> findUserGroupByName(String name) {
        return jdbcClient.sql(SELECT_GROUPS + " WHERE g.name = :name")
                .param("name", name)
                .query(GROUP_ROW)
                .optional();
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

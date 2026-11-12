package org.ntrloc.graph.db.partition.security.repository;

import org.ntrloc.graph.db.partition.changelog.ChangeLog;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Groups and memberships (split from UserStore in docs/ntrloc-drafts-plan.md step 2.3, so a draft's group
// store has a counterpart of its own). Groups form a tree under "everyone" (the baseline's
// security_user_group comment). Writes go through UserService (ArchUnit), whose transaction their
// changelog entries share.
@Component
public class UserGroupStore {

    private static final String PARAM_USER_ID = "userId";
    private static final String PARAM_GROUP_ID = "groupId";

    public record UserGroupRow(UUID id, String name, UUID parentId) {}

    public record MembershipRow(UUID userId, UUID groupId) {}

    private static final String SELECT_GROUPS = "SELECT g.id, g.name, g.parent_id FROM security_user_group g";

    private static final RowMapper<UserGroupRow> GROUP_ROW = (rs, _) -> new UserGroupRow(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("parent_id", UUID.class));

    private final JdbcClient jdbcClient;
    private final ChangeLog changeLog;
    private final UserAccessCache accessCache;

    public UserGroupStore(JdbcClient jdbcClient, ChangeLog changeLog, UserAccessCache accessCache) {
        this.jdbcClient = jdbcClient;
        this.changeLog = changeLog;
        this.accessCache = accessCache;
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

    // The root group, "everyone" (DefaultUserGroupInitializer); the database allows only one group without a
    // parent.
    public UserGroupRow createRootUserGroup(String name) {
        accessCache.changed();
        UUID id = jdbcClient.sql("INSERT INTO security_user_group (name) VALUES (:name) RETURNING id")
                .param("name", name).query(UUID.class).single();
        changeLog.append("group.created", "group:" + id, List.of(id), null, Map.of("name", name));
        return new UserGroupRow(id, name, null);
    }

    // A group directly under the root.
    public UserGroupRow createUserGroup(String name) {
        accessCache.changed();
        return createUserGroup(name, jdbcClient.sql("SELECT id FROM security_user_group WHERE parent_id IS NULL").query(UUID.class).single());
    }

    public UserGroupRow createUserGroup(String name, UUID parentId) {
        return createUserGroup(null, name, parentId);
    }

    // With the id given (a group a draft created keeps its draft id), or a new one if null.
    public UserGroupRow createUserGroup(UUID id, String name, UUID parentId) {
        accessCache.changed();
        id = jdbcClient.sql("INSERT INTO security_user_group (id, name, parent_id) VALUES (COALESCE(CAST(:id AS uuid), uuidv7()), :name, :parentId) RETURNING id")
                .param("id", id)
                .param("name", name)
                .param("parentId", java.util.Objects.requireNonNull(parentId, "every group but the root has a parent"))
                .query(UUID.class).single();
        changeLog.append("group.created", "group:" + id, List.of(id, parentId), null,
                Map.of("name", name, "parentId", parentId, "parentName", groupName(parentId)));
        return new UserGroupRow(id, name, parentId);
    }

    // Moves the group under newParentId. Refused if that would put the group under itself.
    public void setParent(UUID groupId, UUID newParentId) {
        accessCache.changed();
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
        accessCache.changed();
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
        accessCache.changed();
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
        accessCache.changed();
        int added = jdbcClient.sql("INSERT INTO security_user_group_member (user_id, group_id) VALUES (:userId, :groupId) ON CONFLICT DO NOTHING")
                .param(PARAM_USER_ID, userId).param(PARAM_GROUP_ID, groupId).update();
        if (added > 0) {
            logMembership("membership.added", userId, groupId);
        }
    }

    public void removeUserFromUserGroup(UUID userId, UUID groupId) {
        accessCache.changed();
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
        return jdbcClient.sql("SELECT display_name FROM security_user WHERE id = :userId")
                .param(PARAM_USER_ID, userId).query(String.class).optional().orElse(null);
    }

    // Every user not yet in the group joins it (DefaultUserGroupInitializer's backfill into "everyone").
    public void addAllUsersToUserGroup(UUID groupId) {
        accessCache.changed();
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

    // Every membership (a draft's group view is built from them all).
    public List<MembershipRow> listMemberships() {
        return jdbcClient.sql("SELECT user_id, group_id FROM security_user_group_member")
                .query((rs, _) -> new MembershipRow(rs.getObject("user_id", UUID.class), rs.getObject("group_id", UUID.class)))
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

    public List<UserStore.UserRow> listUserGroupMembers(UUID groupId) {
        return jdbcClient.sql("""
                SELECT u.id, u.display_name, u.email, u.is_admin
                FROM security_user u
                JOIN security_user_group_member gm ON gm.user_id = u.id
                WHERE gm.group_id = :groupId
                ORDER BY u.display_name
                """)
                .param(PARAM_GROUP_ID, groupId)
                .query(UserStore.USER_ROW)
                .list();
    }

    public Optional<UserGroupRow> findUserGroupByName(String name) {
        return jdbcClient.sql(SELECT_GROUPS + " WHERE g.name = :name")
                .param("name", name)
                .query(GROUP_ROW)
                .optional();
    }
}

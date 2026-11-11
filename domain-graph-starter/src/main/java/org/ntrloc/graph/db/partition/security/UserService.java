package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.changelog.ChangeLog;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.security.repository.UserStore.UserGroupRow;
import org.ntrloc.graph.db.partition.security.repository.UserStore.UserRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Users and groups as a whole: every change to them goes through here, each in one transaction, so the
// change and its changelog entry (UserStore writes it) commit or roll back together
// (docs/ntrloc-system-model-notes.md §13.6). Removing a user or group also removes their grants, since
// grant tables refer to principals by a bare UUID and nothing cascades (§13.2); the changelog gets one
// entry for the deletion, listing what went with it. The start of Phase 2's service in front of the user
// and group stores (docs/ntrloc-drafts-plan.md).
@Service
public class UserService {

    private final UserStore userStore;
    private final AccessService accessService;
    private final ChangeLog changeLog;

    public UserService(UserStore userStore, AccessService accessService, ChangeLog changeLog) {
        this.userStore = userStore;
        this.accessService = accessService;
        this.changeLog = changeLog;
    }

    @Transactional
    public UserRow createUser(String displayName, String email, boolean isAdmin) {
        return userStore.createUser(displayName, email, isAdmin);
    }

    @Transactional
    public void updateUser(UUID userId, String displayName, String email, boolean isAdmin) {
        userStore.updateUser(userId, displayName, email, isAdmin);
    }

    @Transactional
    public UserGroupRow createRootUserGroup(String name) {
        return userStore.createRootUserGroup(name);
    }

    @Transactional
    public UserGroupRow createUserGroup(String name, UUID parentId) {
        return userStore.createUserGroup(name, parentId);
    }

    @Transactional
    public UserGroupRow renameUserGroup(UUID groupId, String name) {
        return userStore.updateUserGroup(groupId, name);
    }

    @Transactional
    public void setParent(UUID groupId, UUID parentId) {
        userStore.setParent(groupId, parentId);
    }

    @Transactional
    public void addMember(UUID userId, UUID groupId) {
        userStore.addUserToUserGroup(userId, groupId);
    }

    @Transactional
    public void removeMember(UUID userId, UUID groupId) {
        userStore.removeUserFromUserGroup(userId, groupId);
    }

    @Transactional
    public void addEveryUser(UUID groupId) {
        userStore.addAllUsersToUserGroup(groupId);
    }

    @Transactional
    public void deleteUser(UUID userId) {
        UserRow user = userStore.findUserById(userId).orElseThrow();
        List<Map<String, Object>> groups = userStore.getUserGroupsForUser(userId).stream()
                .map(g -> Map.<String, Object>of("id", g.id(), "name", g.name())).toList();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("displayName", user.displayName());
        before.put("email", user.email());
        before.put("isAdmin", user.isAdmin());
        before.put("groups", groups);
        before.put("grants", accessService.getPermissions("USER", userId));
        userStore.deleteUser(userId);
        accessService.deleteGrantsForPrincipals("USER", List.of(userId));
        List<UUID> refs = new ArrayList<>(List.of(userId));
        groups.forEach(g -> refs.add((UUID) g.get("id")));
        changeLog.append("user.deleted", "user:" + userId, refs, before, null);
    }

    /**
     * Deletes the group with its grants. A group with subgroups is refused (UserStore.HasSubgroupsException)
     * unless forced, which deletes the subgroups and their grants too. One changelog entry per group deleted.
     */
    @Transactional
    public void deleteUserGroup(UUID groupId, boolean force) {
        List<Map.Entry<UUID, Map<String, Object>>> deleting = new ArrayList<>();
        for (UUID id : userStore.subtreeOf(groupId)) {
            UserGroupRow group = userStore.findUserGroupById(id).orElseThrow();
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("name", group.name());
            before.put("parentId", group.parentId());
            before.put("parentName", group.parentId() == null ? null : userStore.findUserGroupById(group.parentId()).map(UserGroupRow::name).orElse(null));
            before.put("members", userStore.listUserGroupMembers(id).stream()
                    .map(u -> Map.<String, Object>of("id", u.id(), "name", u.displayName())).toList());
            before.put("grants", accessService.getPermissions("USER_GROUP", id));
            deleting.add(Map.entry(id, before));
        }
        List<UUID> deleted = userStore.deleteUserGroup(groupId, force);
        accessService.deleteGrantsForPrincipals("USER_GROUP", deleted);
        for (var entry : deleting) {
            List<UUID> refs = new ArrayList<>(List.of(entry.getKey()));
            if (entry.getValue().get("parentId") instanceof UUID parent) refs.add(parent);
            @SuppressWarnings("unchecked")
            var members = (List<Map<String, Object>>) entry.getValue().get("members");
            members.forEach(m -> refs.add((UUID) m.get("id")));
            changeLog.append("group.deleted", "group:" + entry.getKey(), refs, entry.getValue(), null);
        }
    }
}

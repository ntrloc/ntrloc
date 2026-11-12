package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.authorization.AccessAdminController.UserGroupMembershipView;
import org.ntrloc.graph.db.partition.security.UserGroupAdminController.AddMemberRequest;
import org.ntrloc.graph.db.partition.security.UserGroupAdminController.CreateUserGroupRequest;
import org.ntrloc.graph.db.partition.security.UserGroupAdminController.MemberView;
import org.ntrloc.graph.db.partition.security.UserGroupAdminController.UpdateParentRequest;
import org.ntrloc.graph.db.partition.security.UserGroupAdminController.UpdateUserGroupRequest;
import org.ntrloc.graph.db.partition.security.UserGroupAdminController.UserGroupView;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore.UserGroupRow;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

// Groups and memberships inside a draft (docs/ntrloc-drafts-plan.md step 2.4): the same shapes as
// UserGroupAdminController and AccessAdminController's user-groups listing, at /api/drafts/{draftId}/admin,
// reading and changing the draft's groups. Admins only. Plus restore, for a group the draft deleted.
@RestController
@RequestMapping("/api/drafts/{draftId}/admin")
public class DraftUserGroupsAdminController {

    private static final String GROUP_NOT_FOUND = "UserGroup not found";

    private final DraftUserService draftUserService;
    private final UserStore userStore;

    public DraftUserGroupsAdminController(DraftUserService draftUserService, UserStore userStore) {
        this.draftUserService = draftUserService;
        this.userStore = userStore;
    }

    @GetMapping("/user-groups")
    List<UserGroupView> list(DraftScope scope) {
        requireAdmin(scope);
        DraftGroups groups = draftUserService.groups(scope.draftId());
        return groups.groups().stream().map(g -> view(groups, g)).toList();
    }

    // The live groups the draft deletes, where live has them, so a screen can show them and offer restore.
    @GetMapping("/user-groups/deleted")
    List<UserGroupView> deleted(DraftScope scope) {
        requireAdmin(scope);
        return draftUserService.deletedLiveGroups(scope.draftId()).stream().map(e -> {
            UserGroupRow g = e.getKey();
            return new UserGroupView(g.id(), g.name(), e.getValue(), g.parentId() == null ? List.of() : List.of(g.parentId()));
        }).toList();
    }

    @PostMapping("/user-groups")
    UserGroupView create(DraftScope scope, @RequestBody CreateUserGroupRequest body) {
        requireAdmin(scope);
        return view(scope, draftUserService.createGroup(scope, body.name(), body.parentUserGroupId()));
    }

    @PutMapping("/user-groups/{groupId}")
    UserGroupView rename(DraftScope scope, @PathVariable UUID groupId, @RequestBody UpdateUserGroupRequest body) {
        requireAdmin(scope);
        return view(scope, draftUserService.renameGroup(scope, groupId, body.name()));
    }

    @PutMapping("/user-groups/{groupId}/parent")
    UserGroupView move(DraftScope scope, @PathVariable UUID groupId, @RequestBody UpdateParentRequest body) {
        requireAdmin(scope);
        return view(scope, draftUserService.moveGroup(scope, groupId, body.parentUserGroupId()));
    }

    // As live: a group with subgroups is refused (409) unless force=true, which deletes them too.
    @DeleteMapping("/user-groups/{groupId}")
    ResponseEntity<Void> delete(DraftScope scope, @PathVariable UUID groupId, @RequestParam(name = "force", defaultValue = "false") boolean force) {
        requireAdmin(scope);
        draftUserService.deleteGroup(scope, groupId, force);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/user-groups/{groupId}/restore")
    UserGroupView restore(DraftScope scope, @PathVariable UUID groupId) {
        requireAdmin(scope);
        return view(scope, draftUserService.restoreGroup(scope, groupId));
    }

    @GetMapping("/user-groups/{groupId}/members")
    List<MemberView> members(DraftScope scope, @PathVariable UUID groupId) {
        requireAdmin(scope);
        DraftGroups groups = draftUserService.groups(scope.draftId());
        if (groups.find(groupId).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND);
        return userStore.findUsersByIds(groups.members(groupId)).stream()
                .sorted(Comparator.comparing(UserStore.UserRow::displayName))
                .map(u -> new MemberView(u.id(), u.displayName(), u.email(), u.isAdmin()))
                .toList();
    }

    @PostMapping("/user-groups/{groupId}/members")
    ResponseEntity<Void> addMember(DraftScope scope, @PathVariable UUID groupId, @RequestBody AddMemberRequest body) {
        requireAdmin(scope);
        if (body.userId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        draftUserService.addMember(scope, body.userId(), groupId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/user-groups/{groupId}/members/{userId}")
    ResponseEntity<Void> removeMember(DraftScope scope, @PathVariable UUID groupId, @PathVariable UUID userId) {
        requireAdmin(scope);
        draftUserService.removeMember(scope, userId, groupId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{userId}/user-groups")
    List<UserGroupMembershipView> groupsOf(DraftScope scope, @PathVariable UUID userId) {
        requireAdmin(scope);
        return draftUserService.groups(scope.draftId()).groupsOf(userId).stream()
                .map(g -> new UserGroupMembershipView(g.id(), g.name())).toList();
    }

    private UserGroupView view(DraftScope scope, UserGroupRow group) {
        return view(draftUserService.groups(scope.draftId()), group);
    }

    private static UserGroupView view(DraftGroups groups, UserGroupRow g) {
        return new UserGroupView(g.id(), g.name(), groups.members(g.id()).size(), g.parentId() == null ? List.of() : List.of(g.parentId()));
    }

    private static void requireAdmin(DraftScope scope) {
        if (!scope.user().isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can manage groups");
    }
}

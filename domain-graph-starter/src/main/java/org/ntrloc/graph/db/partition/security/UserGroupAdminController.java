package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.principal.ExternalPrincipalResolver;

import org.ntrloc.graph.db.partition.authorization.DefaultUserGroupInitializer;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/user-groups")
public class UserGroupAdminController {

    private static final String GROUP_NOT_FOUND = "UserGroup not found";

    // parentIds holds the group's one parent, or nothing for the root ("everyone").
    public record UserGroupView(UUID id, String name, int memberCount, List<UUID> parentIds) {}

    public record CreateUserGroupRequest(String name, UUID parentUserGroupId) {}

    public record UpdateUserGroupRequest(String name) {}

    public record UpdateParentRequest(UUID parentUserGroupId) {}

    public record MemberView(UUID id, String displayName, String email, boolean isAdmin) {}

    public record AddMemberRequest(UUID userId) {}
    private final UserGroupStore userGroupStore;
    private final UserService userService;
    private final ExternalPrincipalResolver principalResolver;
    private final DefaultUserGroupInitializer defaultUserGroupInitializer;

    public UserGroupAdminController(UserGroupStore userGroupStore, UserService userService, ExternalPrincipalResolver principalResolver,
                                DefaultUserGroupInitializer defaultUserGroupInitializer) {
        this.userGroupStore = userGroupStore;
        this.userService = userService;
        this.principalResolver = principalResolver;
        this.defaultUserGroupInitializer = defaultUserGroupInitializer;
    }

    @GetMapping
    List<UserGroupView> listUserGroups(Authentication authentication) {
        requireAdmin(authentication);
        return userGroupStore.listUserGroups().stream()
                .map(this::toView)
                .toList();
    }

    // "everyone" is the one and only top-level group -- it's seeded directly by
    // DefaultUserGroupInitializer at boot, never through this endpoint, so every group created here
    // must nest under something (ultimately "everyone" itself, directly or transitively). Without
    // this check the admin UI's old parent picker ("(Top level)" alongside "everyone") could create
    // a second, sibling root that the tree had no sensible way to relate to "everyone".
    @PostMapping
    UserGroupView createUserGroup(@RequestBody CreateUserGroupRequest body, Authentication authentication) {
        requireAdmin(authentication);
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UserGroup name is required");
        }
        if (body.parentUserGroupId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A parent group is required -- 'everyone' is the only top-level group");
        }
        if (userGroupStore.findUserGroupByName(body.name().trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "UserGroup already exists: " + body.name());
        }
        userGroupStore.findUserGroupById(body.parentUserGroupId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent group not found"));
        return toView(userService.createUserGroup(body.name().trim(), body.parentUserGroupId()));
    }

    @PutMapping("/{groupId}")
    UserGroupView updateUserGroup(@PathVariable("groupId") UUID groupId, @RequestBody UpdateUserGroupRequest body,
                          Authentication authentication) {
        requireAdmin(authentication);
        userGroupStore.findUserGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UserGroup name is required");
        }
        var updated = userService.renameUserGroup(groupId, body.name().trim());
        return toView(updated);
    }

    // Moves the group under another. A parent is required: only "everyone", which the system creates, has
    // none.
    @PutMapping("/{groupId}/parent")
    UserGroupView updateParent(@PathVariable("groupId") UUID groupId, @RequestBody UpdateParentRequest body,
                           Authentication authentication) {
        requireAdmin(authentication);
        var group = userGroupStore.findUserGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        if (body.parentUserGroupId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A parent group is required -- 'everyone' is the only top-level group");
        }
        userGroupStore.findUserGroupById(body.parentUserGroupId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent group not found"));
        try {
            userService.setParent(groupId, body.parentUserGroupId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return toView(userGroupStore.findUserGroupById(group.id()).orElseThrow());
    }

    private UserGroupView toView(UserGroupStore.UserGroupRow g) {
        var parentIds = g.parentId() == null ? List.<UUID>of() : List.of(g.parentId());
        return new UserGroupView(g.id(), g.name(), userGroupStore.listUserGroupMembers(g.id()).size(), parentIds);
    }

    // A group with subgroups is refused (409) unless force=true, which deletes the subgroups too. The
    // deleted groups' grants go with them.
    @DeleteMapping("/{groupId}")
    ResponseEntity<Void> deleteUserGroup(@PathVariable("groupId") UUID groupId,
                                     @RequestParam(name = "force", defaultValue = "false") boolean force,
                                     Authentication authentication) {
        requireAdmin(authentication);
        if (groupId.equals(defaultUserGroupInitializer.getDefaultUserGroupId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete the default group");
        }
        userGroupStore.findUserGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        try {
            userService.deleteUserGroup(groupId, force);
        } catch (UserGroupStore.HasSubgroupsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{groupId}/members")
    List<MemberView> listMembers(@PathVariable("groupId") UUID groupId,
                                 Authentication authentication) {
        requireAdmin(authentication);
        userGroupStore.findUserGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        return userGroupStore.listUserGroupMembers(groupId).stream()
                .map(u -> new MemberView(u.id(), u.displayName(), u.email(), u.isAdmin()))
                .toList();
    }

    @PostMapping("/{groupId}/members")
    ResponseEntity<Void> addMember(@PathVariable("groupId") UUID groupId, @RequestBody AddMemberRequest body,
                                   Authentication authentication) {
        requireAdmin(authentication);
        userGroupStore.findUserGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        userService.addMember(body.userId(), groupId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    ResponseEntity<Void> removeMember(@PathVariable("groupId") UUID groupId, @PathVariable("userId") UUID userId,
                                      Authentication authentication) {
        requireAdmin(authentication);
        userService.removeMember(userId, groupId);
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(Authentication authentication) {
        var principal = principalResolver.resolve(authentication);
        if (!principal.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can manage groups");
        }
    }
}

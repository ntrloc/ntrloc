package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.authorization.DefaultUserGroupInitializer;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/user-groups")
public class UserGroupAdminController {

    private static final String GROUP_NOT_FOUND = "UserGroup not found";

    // parentIds is every group this one is directly nested under -- the schema (security_user_group_
    // member_group) technically allows more than one, but the admin UI only ever offers a single
    // parent picker, so in practice this list has 0 or 1 entries for anything created there.
    public record UserGroupView(UUID id, String name, int memberCount, List<UUID> parentIds) {}

    public record CreateUserGroupRequest(String name, UUID parentUserGroupId) {}

    public record UpdateUserGroupRequest(String name) {}

    public record UpdateParentRequest(UUID parentUserGroupId) {}

    public record MemberView(UUID id, String externalId, String displayName, String email, boolean isSuperuser) {}

    public record AddMemberRequest(UUID userId) {}

    private final SecurityRepository repo;
    private final PrincipalResolver principalResolver;
    private final DefaultUserGroupInitializer defaultUserGroupInitializer;

    public UserGroupAdminController(SecurityRepository repo, PrincipalResolver principalResolver,
                                DefaultUserGroupInitializer defaultUserGroupInitializer) {
        this.repo = repo;
        this.principalResolver = principalResolver;
        this.defaultUserGroupInitializer = defaultUserGroupInitializer;
    }

    @GetMapping
    List<UserGroupView> listUserGroups(ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        return repo.listUserGroups().stream()
                .map(this::toView)
                .toList();
    }

    // "everyone" is the one and only top-level group -- it's seeded directly by
    // DefaultUserGroupInitializer at boot, never through this endpoint, so every group created here
    // must nest under something (ultimately "everyone" itself, directly or transitively). Without
    // this check the admin UI's old parent picker ("(Top level)" alongside "everyone") could create
    // a second, sibling root that the tree had no sensible way to relate to "everyone".
    @PostMapping
    UserGroupView createUserGroup(@RequestBody CreateUserGroupRequest body, ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UserGroup name is required");
        }
        if (body.parentUserGroupId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A parent group is required -- 'everyone' is the only top-level group");
        }
        if (repo.findUserGroupByName(body.name().trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "UserGroup already exists: " + body.name());
        }
        repo.findUserGroupById(body.parentUserGroupId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent group not found"));
        var group = repo.createUserGroup(body.name().trim());
        repo.addUserGroupToUserGroup(group.id(), body.parentUserGroupId());
        return toView(group);
    }

    @PutMapping("/{groupId}")
    UserGroupView updateUserGroup(@PathVariable("groupId") UUID groupId, @RequestBody UpdateUserGroupRequest body,
                          ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        repo.findUserGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UserGroup name is required");
        }
        var updated = repo.updateUserGroup(groupId, body.name().trim());
        return toView(updated);
    }

    // Sets this group's single parent, replacing whatever it was nested under before. A null
    // parentUserGroupId is rejected -- "everyone" is the only group allowed to have no parent (see
    // createUserGroup's own comment), and it's seeded directly by DefaultUserGroupInitializer rather than
    // ever passing through this endpoint, so there's no legitimate caller that needs to clear a
    // group's parent entirely. The admin UI's tree only ever shows/edits one parent per group (see
    // UserGroupView's own comment), so "reparent" here means "clear every existing containing-group
    // edge, then add the new one" rather than a general multi-parent add/remove; that's still
    // exactly what the DAG-shaped schema underneath allows, just used in a restricted way.
    @PutMapping("/{groupId}/parent")
    UserGroupView updateParent(@PathVariable("groupId") UUID groupId, @RequestBody UpdateParentRequest body,
                           ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        var group = repo.findUserGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        if (body.parentUserGroupId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A parent group is required -- 'everyone' is the only top-level group");
        }
        repo.findUserGroupById(body.parentUserGroupId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent group not found"));
        for (var existingParent : repo.listContainingUserGroups(groupId)) {
            repo.removeUserGroupFromUserGroup(groupId, existingParent.id());
        }
        try {
            repo.addUserGroupToUserGroup(groupId, body.parentUserGroupId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return toView(group);
    }

    private UserGroupView toView(SecurityRepository.UserGroupRow g) {
        var parentIds = repo.listContainingUserGroups(g.id()).stream().map(SecurityRepository.UserGroupRow::id).toList();
        return new UserGroupView(g.id(), g.name(), repo.listUserGroupMembers(g.id()).size(), parentIds);
    }

    @DeleteMapping("/{groupId}")
    ResponseEntity<Void> deleteUserGroup(@PathVariable("groupId") UUID groupId,
                                     ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        if (groupId.equals(defaultUserGroupInitializer.getDefaultUserGroupId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete the default group");
        }
        repo.findUserGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        repo.deleteUserGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{groupId}/members")
    List<MemberView> listMembers(@PathVariable("groupId") UUID groupId,
                                 ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        repo.findUserGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        return repo.listUserGroupMembers(groupId).stream()
                .map(u -> new MemberView(u.id(), u.externalId(), u.displayName(), u.email(), u.isSuperuser()))
                .toList();
    }

    @PostMapping("/{groupId}/members")
    ResponseEntity<Void> addMember(@PathVariable("groupId") UUID groupId, @RequestBody AddMemberRequest body,
                                   ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        repo.findUserGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        repo.addUserToUserGroup(body.userId(), groupId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    ResponseEntity<Void> removeMember(@PathVariable("groupId") UUID groupId, @PathVariable("userId") UUID userId,
                                      ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        repo.removeUserFromUserGroup(userId, groupId);
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(ServerHttpRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(request, authentication);
        if (!principal.isSuperuser()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can manage groups");
        }
    }
}

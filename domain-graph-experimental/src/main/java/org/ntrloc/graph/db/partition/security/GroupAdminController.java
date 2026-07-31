package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.authorization.DefaultGroupInitializer;
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
@RequestMapping("/api/admin/groups")
public class GroupAdminController {

    public record GroupView(UUID id, String name, int memberCount) {}

    public record CreateGroupRequest(String name) {}

    public record UpdateGroupRequest(String name) {}

    public record MemberView(UUID id, String externalId, String displayName, String email) {}

    public record AddMemberRequest(UUID userId) {}

    private final SecurityRepository repo;
    private final PrincipalResolver principalResolver;
    private final DefaultGroupInitializer defaultGroupInitializer;

    public GroupAdminController(SecurityRepository repo, PrincipalResolver principalResolver,
                                DefaultGroupInitializer defaultGroupInitializer) {
        this.repo = repo;
        this.principalResolver = principalResolver;
        this.defaultGroupInitializer = defaultGroupInitializer;
    }

    @GetMapping
    List<GroupView> listGroups(ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        return repo.listGroups().stream()
                .map(g -> new GroupView(g.id(), g.name(), repo.listGroupMembers(g.id()).size()))
                .toList();
    }

    @PostMapping
    GroupView createGroup(@RequestBody CreateGroupRequest body, ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Group name is required");
        }
        if (repo.findGroupByName(body.name().trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Group already exists: " + body.name());
        }
        var group = repo.createGroup(body.name().trim());
        return new GroupView(group.id(), group.name(), 0);
    }

    @PutMapping("/{groupId}")
    GroupView updateGroup(@PathVariable("groupId") UUID groupId, @RequestBody UpdateGroupRequest body,
                          ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        repo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Group name is required");
        }
        var updated = repo.updateGroup(groupId, body.name().trim());
        return new GroupView(updated.id(), updated.name(), repo.listGroupMembers(groupId).size());
    }

    @DeleteMapping("/{groupId}")
    ResponseEntity<Void> deleteGroup(@PathVariable("groupId") UUID groupId,
                                     ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        if (groupId.equals(defaultGroupInitializer.getDefaultGroupId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete the default group");
        }
        repo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        repo.deleteGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{groupId}/members")
    List<MemberView> listMembers(@PathVariable("groupId") UUID groupId,
                                 ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        repo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        return repo.listGroupMembers(groupId).stream()
                .map(u -> new MemberView(u.id(), u.externalId(), u.displayName(), u.email()))
                .toList();
    }

    @PostMapping("/{groupId}/members")
    ResponseEntity<Void> addMember(@PathVariable("groupId") UUID groupId, @RequestBody AddMemberRequest body,
                                   ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        repo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        repo.addUserToGroup(body.userId(), groupId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    ResponseEntity<Void> removeMember(@PathVariable("groupId") UUID groupId, @PathVariable("userId") UUID userId,
                                      ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        repo.removeUserFromGroup(userId, groupId);
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(ServerHttpRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(request, authentication);
        if (!principal.isSuperuser()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can manage groups");
        }
    }
}

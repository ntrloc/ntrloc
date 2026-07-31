package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AccessAdminController {

    // --- Request/response records ---

    public record GroupPermissionView(String itemTypeName, UUID itemTypeId, List<String> operations) {}

    public record PermissionRequest(UUID itemTypeId, String operation) {}

    public record OperationWithVia(String operation, List<String> via) {}

    public record UserPermissionView(String itemTypeName, UUID itemTypeId, List<OperationWithVia> operations) {}

    public record GroupMembershipView(UUID id, String name) {}

    public record ItemTypeView(UUID id, String name) {}

    // --- Dependencies ---

    private final AuthorizationRepository authRepo;
    private final SecurityRepository securityRepo;
    private final SchemaRepository schemaRepo;
    private final PrincipalResolver principalResolver;

    public AccessAdminController(AuthorizationRepository authRepo, SecurityRepository securityRepo,
                                 SchemaRepository schemaRepo, PrincipalResolver principalResolver) {
        this.authRepo = authRepo;
        this.securityRepo = securityRepo;
        this.schemaRepo = schemaRepo;
        this.principalResolver = principalResolver;
    }

    // --- Group permissions ---

    @GetMapping("/groups/{groupId}/permissions")
    List<GroupPermissionView> getGroupPermissions(@PathVariable("groupId") UUID groupId,
                                                  ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));

        var grants = authRepo.getGrantsForPrincipal("GROUP", groupId);

        // Group by item type, collect operations
        Map<UUID, GroupPermissionView> byItemType = new LinkedHashMap<>();
        for (var g : grants) {
            byItemType.compute(g.itemTypeId(), (k, existing) -> {
                if (existing == null) {
                    var ops = new ArrayList<String>();
                    ops.add(g.operation());
                    return new GroupPermissionView(g.itemTypeName(), g.itemTypeId(), ops);
                } else {
                    existing.operations().add(g.operation());
                    return existing;
                }
            });
        }
        return new ArrayList<>(byItemType.values());
    }

    @PostMapping("/groups/{groupId}/permissions")
    ResponseEntity<Void> grantGroupPermission(@PathVariable("groupId") UUID groupId,
                                               @RequestBody PermissionRequest body,
                                               ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        if (body.itemTypeId() == null || body.operation() == null || body.operation().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "itemTypeId and operation are required");
        }

        // Find or create a marker for this item type
        UUID markerId = authRepo.findMarkerForItemType(body.itemTypeId())
                .orElseGet(() -> {
                    String markerName = "access-" + body.itemTypeId().toString();
                    var marker = authRepo.createMarker(markerName, "Auto-created for admin permission grant");
                    authRepo.assignMarkerToItemTypeIfAbsent(body.itemTypeId(), marker.id());
                    return marker.id();
                });

        // Create the grant (idempotent)
        authRepo.grantIfAbsent(markerId, "GROUP", groupId, body.operation());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/groups/{groupId}/permissions")
    ResponseEntity<Void> revokeGroupPermission(@PathVariable("groupId") UUID groupId,
                                                @RequestBody PermissionRequest body,
                                                ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        if (body.itemTypeId() == null || body.operation() == null || body.operation().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "itemTypeId and operation are required");
        }

        UUID markerId = authRepo.findMarkerForItemType(body.itemTypeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No marker found for item type"));

        UUID grantId = authRepo.findGrant(markerId, "GROUP", groupId, body.operation())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grant not found"));

        authRepo.deleteGrant(grantId);
        return ResponseEntity.noContent().build();
    }

    // --- User effective permissions ---

    @GetMapping("/users/{userId}/permissions")
    List<UserPermissionView> getUserEffectivePermissions(@PathVariable("userId") UUID userId,
                                                         ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);

        // Get all groups user belongs to
        var userGroups = securityRepo.getGroupsForUser(userId);
        if (userGroups.isEmpty()) {
            return List.of();
        }

        Set<UUID> groupIds = userGroups.stream().map(SecurityRepository.GroupRow::id).collect(Collectors.toSet());
        Map<UUID, String> groupNameById = userGroups.stream()
                .collect(Collectors.toMap(SecurityRepository.GroupRow::id, SecurityRepository.GroupRow::name));

        // Get all grants for those groups
        var allGrants = authRepo.getGrantsForGroups(groupIds);

        // We also need to know which group each grant belongs to. Re-query per group to get that mapping.
        // Actually, let's restructure: query grants per group to know which group provides which grant.
        record GrantWithGroup(UUID itemTypeId, String itemTypeName, String operation, String groupName) {}
        List<GrantWithGroup> grantsWithGroup = new ArrayList<>();
        for (var group : userGroups) {
            var groupGrants = authRepo.getGrantsForPrincipal("GROUP", group.id());
            for (var g : groupGrants) {
                grantsWithGroup.add(new GrantWithGroup(g.itemTypeId(), g.itemTypeName(), g.operation(), group.name()));
            }
        }

        // Group by (itemTypeId, operation) -> list of group names providing it
        record ItemOp(UUID itemTypeId, String itemTypeName, String operation) {}
        Map<ItemOp, List<String>> viaMap = new LinkedHashMap<>();
        for (var gwg : grantsWithGroup) {
            var key = new ItemOp(gwg.itemTypeId(), gwg.itemTypeName(), gwg.operation());
            viaMap.computeIfAbsent(key, k -> new ArrayList<>()).add(gwg.groupName());
        }

        // Group by item type
        Map<UUID, UserPermissionView> byItemType = new LinkedHashMap<>();
        for (var entry : viaMap.entrySet()) {
            var key = entry.getKey();
            var viaGroups = entry.getValue();
            byItemType.compute(key.itemTypeId(), (k, existing) -> {
                var opWithVia = new OperationWithVia(key.operation(), viaGroups);
                if (existing == null) {
                    var ops = new ArrayList<OperationWithVia>();
                    ops.add(opWithVia);
                    return new UserPermissionView(key.itemTypeName(), key.itemTypeId(), ops);
                } else {
                    existing.operations().add(opWithVia);
                    return existing;
                }
            });
        }
        return new ArrayList<>(byItemType.values());
    }

    // --- User group memberships ---

    @GetMapping("/users/{userId}/groups")
    List<GroupMembershipView> getUserGroups(@PathVariable("userId") UUID userId,
                                            ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        return securityRepo.getGroupsForUser(userId).stream()
                .map(g -> new GroupMembershipView(g.id(), g.name()))
                .toList();
    }

    // --- Schema item types listing ---

    @GetMapping("/schema/item-types")
    List<ItemTypeView> listItemTypes(ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        return schemaRepo.getAllItems().stream()
                .map(item -> new ItemTypeView(item.id(), item.name()))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    // --- Helpers ---

    private void requireAdmin(ServerHttpRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(request, authentication);
        if (!principal.isSuperuser()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can manage access");
        }
    }
}

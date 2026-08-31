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
import org.springframework.web.bind.annotation.PutMapping;
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

    private static final String GROUP_PRINCIPAL_TYPE = "GROUP";
    private static final String GROUP_NOT_FOUND = "Group not found";

    // --- Request/response records ---

    public record GroupPermissionView(String itemTypeName, UUID itemTypeId, List<String> operations) {}

    public record PermissionRequest(UUID itemTypeId, String operation) {}

    public record OperationWithVia(String operation, List<String> via) {}

    public record UserPermissionView(String itemTypeName, UUID itemTypeId, List<OperationWithVia> operations) {}

    public record GroupMembershipView(UUID id, String name) {}

    public record ItemTypeView(UUID id, String name) {}

    public record MarkerPropertyGrantView(UUID propertyId, boolean canRead, boolean canWrite) {}

    public record MarkerPropertyGrantRequest(boolean canRead, boolean canWrite) {}

    public record LinkPerspectiveGrantView(UUID perspectiveId, boolean canCreate, boolean canRead, boolean canDelete) {}

    public record LinkPerspectiveGrantRequest(boolean canCreate, boolean canRead, boolean canDelete) {}

    public record MarkerItemGrantView(boolean canRead, boolean canDelete) {}

    public record MarkerItemGrantRequest(boolean canRead, boolean canDelete) {}

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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));

        var grants = authRepo.getItemTypeGrantsForPrincipal(GROUP_PRINCIPAL_TYPE, groupId);

        // Group by item type, collect operations
        Map<UUID, GroupPermissionView> byItemType = new LinkedHashMap<>();
        for (var g : grants) {
            byItemType.compute(g.itemTypeId(), (k, existing) -> {
                if (existing == null) {
                    var ops = new ArrayList<String>();
                    ops.add(g.permission());
                    return new GroupPermissionView(g.itemTypeName(), g.itemTypeId(), ops);
                } else {
                    existing.operations().add(g.permission());
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        requireValidPermissionRequest(body);

        authRepo.grantItemTypeIfAbsent(body.itemTypeId(), GROUP_PRINCIPAL_TYPE, groupId, body.operation());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/groups/{groupId}/permissions")
    ResponseEntity<Void> revokeGroupPermission(@PathVariable("groupId") UUID groupId,
                                                @RequestBody PermissionRequest body,
                                                ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));
        requireValidPermissionRequest(body);

        UUID grantId = authRepo.findItemTypeGrant(body.itemTypeId(), GROUP_PRINCIPAL_TYPE, groupId, body.operation())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grant not found"));

        authRepo.deleteItemTypeGrant(grantId);
        return ResponseEntity.noContent().build();
    }

    // --- Group marker-scoped property grants (Read/Write per property, under a marker
    // scoped to the item type being viewed) ---

    @GetMapping("/groups/{groupId}/markers/{markerId}/properties")
    List<MarkerPropertyGrantView> getGroupMarkerPropertyGrants(@PathVariable("groupId") UUID groupId,
                                                                @PathVariable("markerId") UUID markerId,
                                                                ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));

        return authRepo.getPropertyGrantsForMarker(markerId, GROUP_PRINCIPAL_TYPE, groupId).stream()
                .map(r -> new MarkerPropertyGrantView(r.propertyId(), r.canRead(), r.canWrite()))
                .toList();
    }

    @PutMapping("/groups/{groupId}/markers/{markerId}/properties/{propertyId}")
    ResponseEntity<Void> setGroupMarkerPropertyGrant(@PathVariable("groupId") UUID groupId,
                                                      @PathVariable("markerId") UUID markerId,
                                                      @PathVariable("propertyId") UUID propertyId,
                                                      @RequestBody MarkerPropertyGrantRequest body,
                                                      ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));

        UUID markerGrantId = authRepo.ensureMarkerGrant(markerId, GROUP_PRINCIPAL_TYPE, groupId);
        authRepo.grantPropertyAccess(markerGrantId, propertyId, body.canRead(), body.canWrite());
        return ResponseEntity.noContent().build();
    }

    // --- Group marker-scoped item-level grants (Read/Delete of the item carrying the marker --
    // item_can_read / item_can_delete live directly on marker_grant, one pair per (marker, principal)) ---

    @GetMapping("/groups/{groupId}/markers/{markerId}/item-permissions")
    MarkerItemGrantView getGroupMarkerItemGrant(@PathVariable("groupId") UUID groupId,
                                                @PathVariable("markerId") UUID markerId,
                                                ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));

        var row = authRepo.getItemPermissionsForMarker(markerId, GROUP_PRINCIPAL_TYPE, groupId);
        return new MarkerItemGrantView(row.canRead(), row.canDelete());
    }

    @PutMapping("/groups/{groupId}/markers/{markerId}/item-permissions")
    ResponseEntity<Void> setGroupMarkerItemGrant(@PathVariable("groupId") UUID groupId,
                                                 @PathVariable("markerId") UUID markerId,
                                                 @RequestBody MarkerItemGrantRequest body,
                                                 ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));

        UUID markerGrantId = authRepo.ensureMarkerGrant(markerId, GROUP_PRINCIPAL_TYPE, groupId);
        authRepo.setItemPermissions(markerGrantId, body.canRead(), body.canDelete());
        return ResponseEntity.noContent().build();
    }

    // --- Group marker-scoped link-property grants -- same shape as item properties above, just
    // against a link type's own properties (marker_grant_link_property) ---

    @GetMapping("/groups/{groupId}/markers/{markerId}/link-properties")
    List<MarkerPropertyGrantView> getGroupMarkerLinkPropertyGrants(@PathVariable("groupId") UUID groupId,
                                                                    @PathVariable("markerId") UUID markerId,
                                                                    ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));

        return authRepo.getLinkPropertyGrantsForMarker(markerId, GROUP_PRINCIPAL_TYPE, groupId).stream()
                .map(r -> new MarkerPropertyGrantView(r.propertyId(), r.canRead(), r.canWrite()))
                .toList();
    }

    @PutMapping("/groups/{groupId}/markers/{markerId}/link-properties/{propertyId}")
    ResponseEntity<Void> setGroupMarkerLinkPropertyGrant(@PathVariable("groupId") UUID groupId,
                                                          @PathVariable("markerId") UUID markerId,
                                                          @PathVariable("propertyId") UUID propertyId,
                                                          @RequestBody MarkerPropertyGrantRequest body,
                                                          ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));

        UUID markerGrantId = authRepo.ensureMarkerGrant(markerId, GROUP_PRINCIPAL_TYPE, groupId);
        authRepo.grantLinkPropertyAccess(markerGrantId, propertyId, body.canRead(), body.canWrite());
        return ResponseEntity.noContent().build();
    }

    // --- Group marker-scoped link-perspective grants (Create/Read/Delete per perspective) ---

    @GetMapping("/groups/{groupId}/markers/{markerId}/link-perspectives")
    List<LinkPerspectiveGrantView> getGroupMarkerLinkPerspectiveGrants(@PathVariable("groupId") UUID groupId,
                                                                        @PathVariable("markerId") UUID markerId,
                                                                        ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));

        return authRepo.getLinkPerspectiveGrantsForMarker(markerId, GROUP_PRINCIPAL_TYPE, groupId).stream()
                .map(r -> new LinkPerspectiveGrantView(r.perspectiveId(), r.canCreate(), r.canRead(), r.canDelete()))
                .toList();
    }

    @PutMapping("/groups/{groupId}/markers/{markerId}/link-perspectives/{perspectiveId}")
    ResponseEntity<Void> setGroupMarkerLinkPerspectiveGrant(@PathVariable("groupId") UUID groupId,
                                                             @PathVariable("markerId") UUID markerId,
                                                             @PathVariable("perspectiveId") UUID perspectiveId,
                                                             @RequestBody LinkPerspectiveGrantRequest body,
                                                             ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));

        UUID markerGrantId = authRepo.ensureMarkerGrant(markerId, GROUP_PRINCIPAL_TYPE, groupId);
        authRepo.grantLinkPerspectiveAccess(markerGrantId, perspectiveId, body.canCreate(), body.canRead(), body.canDelete());
        return ResponseEntity.noContent().build();
    }

    // --- Group marker-scoped transition-execute grants (existence-only, see
    // AuthorizationRepository.grantTransitionExecute's own comment) ---

    @GetMapping("/groups/{groupId}/markers/{markerId}/transitions")
    Set<UUID> getGroupMarkerTransitionGrants(@PathVariable("groupId") UUID groupId,
                                              @PathVariable("markerId") UUID markerId,
                                              ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));

        return authRepo.getTransitionGrantsForMarker(markerId, GROUP_PRINCIPAL_TYPE, groupId);
    }

    @PostMapping("/groups/{groupId}/markers/{markerId}/transitions/{transitionId}")
    ResponseEntity<Void> grantGroupMarkerTransition(@PathVariable("groupId") UUID groupId,
                                                     @PathVariable("markerId") UUID markerId,
                                                     @PathVariable("transitionId") UUID transitionId,
                                                     ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));

        UUID markerGrantId = authRepo.ensureMarkerGrant(markerId, GROUP_PRINCIPAL_TYPE, groupId);
        authRepo.grantTransitionExecute(markerGrantId, transitionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/groups/{groupId}/markers/{markerId}/transitions/{transitionId}")
    ResponseEntity<Void> revokeGroupMarkerTransition(@PathVariable("groupId") UUID groupId,
                                                      @PathVariable("markerId") UUID markerId,
                                                      @PathVariable("transitionId") UUID transitionId,
                                                      ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        securityRepo.findGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND));

        authRepo.findMarkerGrant(markerId, GROUP_PRINCIPAL_TYPE, groupId)
                .ifPresent(markerGrantId -> authRepo.revokeTransitionExecute(markerGrantId, transitionId));
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

        // We also need to know which group each grant belongs to. Re-query per group to get that mapping.
        // Actually, let's restructure: query grants per group to know which group provides which grant.
        record GrantWithGroup(UUID itemTypeId, String itemTypeName, String operation, String groupName) {}
        List<GrantWithGroup> grantsWithGroup = new ArrayList<>();
        for (var group : userGroups) {
            var groupGrants = authRepo.getItemTypeGrantsForPrincipal(GROUP_PRINCIPAL_TYPE, group.id());
            for (var g : groupGrants) {
                grantsWithGroup.add(new GrantWithGroup(g.itemTypeId(), g.itemTypeName(), g.permission(), group.name()));
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

    private void requireValidPermissionRequest(PermissionRequest body) {
        if (body.itemTypeId() == null || body.operation() == null || body.operation().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "itemTypeId and operation are required");
        }
        if (!PermissionService.ITEM_TYPE_READ.equals(body.operation()) && !PermissionService.ITEM_TYPE_CREATE.equals(body.operation())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "operation must be one of: " + PermissionService.ITEM_TYPE_READ + ", " + PermissionService.ITEM_TYPE_CREATE);
        }
    }
}

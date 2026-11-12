package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.partition.authorization.repository.GrantsView;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.schema.SchemaService;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminLinkView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.ntrloc.graph.principal.NtrlocPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// What the grants API says, whichever address it's served at: live (GrantsAdminController) or in a draft
// (DraftGrantsAdminController). The shapes, the principal syntax ("user:{id}", "group:{id}"), and the
// reads, given the grants to read; and what a permission must name to be granted.
@Component
public class GrantsApi {

    public record PrincipalRef(String kind, UUID id) {}

    public record PrincipalPermissions(PrincipalRef principal, List<Permission> permissions) {}

    public record SetPermissionRequest(PrincipalRef principal, Permission permission, Boolean value) {}

    // What there is to grant against.
    public record Named(UUID id, String name) {}

    public record PerspectiveTarget(UUID id, String name, List<Named> properties) {}

    public record TransitionTarget(UUID id, String name, String fromStateName, String toStateName) {}

    public record StateMachineTarget(UUID id, String name, List<TransitionTarget> transitions) {}

    public record ItemTypeTarget(UUID id, String name, List<Named> properties, List<PerspectiveTarget> perspectives,
                                 List<StateMachineTarget> stateMachines) {}

    public record MarkerTarget(UUID id, String name, String description, String scopeKind, UUID scopeId) {}

    public record GrantTargets(List<ItemTypeTarget> itemTypes, List<MarkerTarget> markers) {}

    private final MarkerStoreReader markerReader;
    private final SchemaService schemaService;
    private final UserStore userStore;
    private final UserGroupStore userGroupStore;

    public GrantsApi(MarkerStoreReader markerReader, SchemaService schemaService, UserStore userStore, UserGroupStore userGroupStore) {
        this.markerReader = markerReader;
        this.schemaService = schemaService;
        this.userStore = userStore;
        this.userGroupStore = userGroupStore;
    }

    /** Each named principal's own permissions, in the order asked for. */
    public List<PrincipalPermissions> permissions(GrantsView grants, List<String> principals) {
        return permissions(grants, principals, this::exists);
    }

    /** As above, with `exists` saying which principals there are (a draft has groups live doesn't). */
    public List<PrincipalPermissions> permissions(GrantsView grants, List<String> principals, java.util.function.Predicate<PrincipalRef> exists) {
        return principals.stream().map(GrantsApi::parsePrincipal).map(principal -> {
            requireExists(principal, exists);
            return new PrincipalPermissions(principal, grants.permissionsOf(principalType(principal), principal.id()).stream()
                    .sorted(Comparator.comparing(Permission::toString)).toList());
        }).toList();
    }

    /** What there is to grant against. */
    public GrantTargets targets() {
        var schema = schemaService.getAdminSchema();
        Map<UUID, AdminLinkView> linksById = schema.links().stream().collect(Collectors.toMap(AdminLinkView::id, Function.identity()));
        var itemTypes = schema.items().stream()
                .filter(item -> !item.abstractType())
                .map(item -> itemTypeTarget(item, linksById))
                .sorted(Comparator.comparing(ItemTypeTarget::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        var markers = markerReader.getAllMarkers().stream()
                .map(m -> new MarkerTarget(m.id(), m.name(), m.description(), m.scopeKind(), m.scopeId()))
                .sorted(Comparator.comparing(MarkerTarget::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new GrantTargets(itemTypes, markers);
    }

    /**
     * A request to set a permission that names an existing principal and something there is to grant
     * against -- checked here rather than left to foreign keys, which a draft's overrides don't have.
     */
    public void requireValid(SetPermissionRequest request) {
        requireValid(request, this::exists);
    }

    /** As above, with `exists` saying which principals there are. */
    public void requireValid(SetPermissionRequest request, java.util.function.Predicate<PrincipalRef> exists) {
        if (request.principal() == null || request.principal().id() == null || request.permission() == null || request.value() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "principal, permission and value are required");
        }
        principalType(request.principal());
        requireExists(request.principal(), exists);
        if (!grantable(request.permission(), targets())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The permission names something that can't be granted");
        }
    }

    /** The principal and everything the permission names still exist. */
    public boolean isGrantable(String principalType, UUID principalId, Permission permission, GrantTargets targets) {
        boolean principalExists = "USER".equals(principalType)
                ? userStore.findUserById(principalId).isPresent()
                : userGroupStore.findUserGroupById(principalId).isPresent();
        return principalExists && grantable(permission, targets);
    }

    private static boolean grantable(Permission p, GrantTargets targets) {
        if (Permission.ITEM_TYPE.equals(p.kind())) {
            return targets.itemTypes().stream().anyMatch(t -> t.id().equals(p.itemTypeId()));
        }
        if (targets.markers().stream().noneMatch(m -> m.id().equals(p.markerId()))) return false;
        var itemTypes = targets.itemTypes().stream();
        return switch (p.area()) {
            case Permission.AREA_ITEM -> true;
            case Permission.AREA_PROPERTY -> itemTypes.flatMap(t -> t.properties().stream()).anyMatch(n -> n.id().equals(p.targetId()));
            case Permission.AREA_LINK_PROPERTY -> itemTypes.flatMap(t -> t.perspectives().stream()).flatMap(v -> v.properties().stream())
                    .anyMatch(n -> n.id().equals(p.targetId()));
            case Permission.AREA_PERSPECTIVE -> itemTypes.flatMap(t -> t.perspectives().stream()).anyMatch(v -> v.id().equals(p.targetId()));
            case Permission.AREA_TRANSITION -> itemTypes.flatMap(t -> t.stateMachines().stream()).flatMap(m -> m.transitions().stream())
                    .anyMatch(t -> t.id().equals(p.targetId()));
            case Permission.AREA_START -> itemTypes.flatMap(t -> t.stateMachines().stream()).anyMatch(m -> m.id().equals(p.targetId()));
            default -> false;
        };
    }

    public static void requireAdmin(NtrlocPrincipal user) {
        if (!user.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can manage grants");
    }

    private static ItemTypeTarget itemTypeTarget(AdminItemDefinitionView item, Map<UUID, AdminLinkView> linksById) {
        List<PerspectiveTarget> perspectives = new ArrayList<>();
        if (item.links() != null) {
            item.links().forEach((name, views) -> views.forEach(view -> {
                var link = linksById.get(view.linkId());
                perspectives.add(new PerspectiveTarget(view.id(), name, link == null ? List.of() : properties(link.properties(), link.groups())));
            }));
        }
        perspectives.sort(Comparator.comparing(PerspectiveTarget::name, String.CASE_INSENSITIVE_ORDER));
        List<StateMachineTarget> stateMachines = item.stateMachines() == null ? List.of() : item.stateMachines().stream()
                .map(sm -> new StateMachineTarget(sm.id(), sm.name(), sm.states().stream()
                        .filter(state -> !"START".equals(state.kind()))
                        .flatMap(state -> state.transitions().stream()
                                .map(t -> new TransitionTarget(t.id(), t.name(), state.name(), t.toStateName())))
                        .toList()))
                .toList();
        return new ItemTypeTarget(item.id(), item.name(), properties(item.properties(), item.groups()), perspectives, stateMachines);
    }

    // Grantable properties are leaves; a property inside a group is named by its path ("address.city").
    private static List<Named> properties(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups) {
        List<Named> out = new ArrayList<>();
        collectProperties("", properties, groups, out);
        return out;
    }

    private static void collectProperties(String prefix, List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups, List<Named> out) {
        if (properties != null) properties.forEach(p -> out.add(new Named(p.id(), prefix + p.name())));
        if (groups != null) groups.forEach(g -> collectProperties(prefix + g.name() + ".", g.properties(), g.groups(), out));
    }

    public static PrincipalRef parsePrincipal(String value) {
        int colon = value.indexOf(':');
        try {
            if (colon > 0) {
                var principal = new PrincipalRef(value.substring(0, colon), UUID.fromString(value.substring(colon + 1)));
                principalType(principal);
                return principal;
            }
        } catch (IllegalArgumentException | ResponseStatusException e) {
            // fall through to the one error message below
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "principal is user:{id} or group:{id}, not " + value);
    }

    public static String principalType(PrincipalRef principal) {
        return switch (principal.kind() == null ? "" : principal.kind()) {
            case "user" -> "USER";
            case "group" -> "USER_GROUP";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "principal kind is user or group");
        };
    }

    public void requireExists(PrincipalRef principal) {
        requireExists(principal, this::exists);
    }

    private static void requireExists(PrincipalRef principal, java.util.function.Predicate<PrincipalRef> exists) {
        if (!exists.test(principal)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such " + principal.kind());
    }

    public boolean isUser(UUID userId) {
        return userStore.findUserById(userId).isPresent();
    }

    private boolean exists(PrincipalRef principal) {
        return "user".equals(principal.kind())
                ? userStore.findUserById(principal.id()).isPresent()
                : userGroupStore.findUserGroupById(principal.id()).isPresent();
    }

}

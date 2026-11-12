package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.authorization.GrantsApi;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// What a draft grant override depends on in live, and whether live has moved underneath it since
// (docs/ntrloc-system-model-notes.md §13.3-13.4), read from the live changelog. Draft work: it only reads
// live, and live never calls it.
@Component
public class GrantDependencyTracker {

    /** Why an override needs reconciling. `id`/`name`: the user or group concerned, where there is one. */
    public record Reason(Kind kind, UUID id, String name) {}

    public enum Kind {
        /** The override's user or group has been deleted. */
        PRINCIPAL_DELETED,
        /** What it grants on (an item type, marker, property, ...) has been deleted. */
        TARGET_DELETED,
        /** The user or group itself changed: moved, its members or subgroups, a user's groups or admin flag. */
        PRINCIPAL_CHANGED,
        /** A group the effect runs through was moved, or a subgroup's members or place changed. */
        GROUP_CHANGED,
        /** A group the effect ran through has been deleted. */
        GROUP_DELETED,
        /** Live now gives the permission through the principal's groups, or no longer does. */
        INHERITED_CHANGED
    }

    // Which changelog entries count, for each way an override depends on a user or group. Renames never
    // count: a name changes nothing a grant does.
    private enum Role {
        GROUP("l.kind IN ('group.moved', 'group.deleted', 'group.created', 'membership.added', 'membership.removed')"),
        USER("(l.kind IN ('membership.added', 'membership.removed', 'user.deleted') OR (l.kind = 'user.updated' AND jsonb_exists(l.after, 'isAdmin')))"),
        PATH("l.kind IN ('group.moved', 'group.deleted') AND l.element_key = 'group:' || d.id"),
        BELOW("l.kind IN ('group.moved', 'group.deleted', 'group.created', 'membership.added', 'membership.removed')");

        final String filter;

        Role(String filter) {
            this.filter = filter;
        }
    }

    private final UserStore userStore;
    private final UserGroupStore userGroupStore;
    private final GrantStoreReader liveGrants;
    private final GrantsApi grantsApi;
    private final JdbcClient jdbcClient;

    public GrantDependencyTracker(UserStore userStore, UserGroupStore userGroupStore, GrantStoreReader liveGrants, GrantsApi grantsApi, JdbcClient jdbcClient) {
        this.userStore = userStore;
        this.userGroupStore = userGroupStore;
        this.liveGrants = liveGrants;
        this.grantsApi = grantsApi;
        this.jdbcClient = jdbcClient;
    }

    /** What the override depends on now, counted from `since`, the draft's starting position. */
    public GrantDependencies capture(String principalType, UUID principalId, Permission permission, long since) {
        List<UUID> path = pathOf(principalType, principalId);
        List<UUID> below = isUser(principalType) ? List.of() : userGroupStore.subtreeOf(principalId).stream().filter(id -> !id.equals(principalId)).toList();
        return new GrantDependencies(since,
                touches(isUser(principalType) ? Role.USER : Role.GROUP, List.of(principalId), since),
                touches(Role.PATH, path, since),
                touches(Role.BELOW, below, since),
                inherited(path, permission));
    }

    /** For each override, why it needs reconciling with live as it is now; overrides that don't are left out. */
    public Map<DraftGrantOverride, List<Reason>> check(Collection<DraftGrantOverride> overrides) {
        return check(overrides, java.util.Set.of());
    }

    /** As above; a grant to a group the draft makes (`draftGroups`) depends on nothing in live. */
    public Map<DraftGrantOverride, List<Reason>> check(Collection<DraftGrantOverride> overrides, java.util.Set<UUID> draftGroups) {
        if (overrides.isEmpty()) return Map.of();
        var targets = grantsApi.targets();
        Map<DraftGrantOverride, List<Reason>> found = new LinkedHashMap<>();
        for (var o : overrides) {
            if (draftGroups.contains(o.principalId())) continue;
            List<Reason> reasons = check(o, targets);
            if (!reasons.isEmpty()) found.put(o, reasons);
        }
        return found;
    }

    private List<Reason> check(DraftGrantOverride o, GrantsApi.GrantTargets targets) {
        boolean user = isUser(o.principalType());
        boolean exists = user ? userStore.findUserById(o.principalId()).isPresent() : userGroupStore.findUserGroupById(o.principalId()).isPresent();
        if (!exists) return List.of(new Reason(Kind.PRINCIPAL_DELETED, o.principalId(), null));
        if (!grantsApi.isGrantable(o.principalType(), o.principalId(), o.permission(), targets)) return List.of(new Reason(Kind.TARGET_DELETED, null, null));

        var recorded = o.dependsOn();
        List<Reason> reasons = new ArrayList<>();
        if (!touches(user ? Role.USER : Role.GROUP, List.of(o.principalId()), recorded.since()).equals(recorded.principal())) {
            // The principal itself changed: that covers its place and members, so say just that.
            reasons.add(new Reason(Kind.PRINCIPAL_CHANGED, o.principalId(), null));
        } else {
            reasons.addAll(changed(Role.PATH, recorded.path(), recorded.since()));
            reasons.addAll(changed(Role.BELOW, recorded.below(), recorded.since()));
        }
        if (inherited(pathOf(o.principalType(), o.principalId()), o.permission()) != recorded.inherited()) {
            reasons.add(new Reason(Kind.INHERITED_CHANGED, null, null));
        }
        return reasons;
    }

    // The groups whose relevant changes have grown in number since they were recorded, each with its name.
    private List<Reason> changed(Role role, Map<UUID, Integer> recorded, long since) {
        Map<UUID, Integer> now = touches(role, recorded.keySet(), since);
        List<Reason> reasons = new ArrayList<>();
        recorded.forEach((id, count) -> {
            if (!count.equals(now.get(id))) {
                var group = userGroupStore.findUserGroupById(id);
                reasons.add(new Reason(group.isPresent() ? Kind.GROUP_CHANGED : Kind.GROUP_DELETED, id, group.map(UserGroupStore.UserGroupRow::name).orElse(null)));
            }
        });
        return reasons;
    }

    // For each id, how many changelog entries after `since` concern it in this role.
    private Map<UUID, Integer> touches(Role role, Collection<UUID> ids, long since) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, Integer> counts = new HashMap<>();
        ids.forEach(id -> counts.put(id, 0));
        jdbcClient.sql("SELECT d.id, COUNT(l.seq) AS touches FROM unnest(CAST(:ids AS uuid[])) AS d(id)"
                        + " JOIN config_change_log l ON l.refs @> ARRAY[d.id] AND l.seq > :since AND " + role.filter
                        + " GROUP BY d.id")
                .param("ids", ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}")))
                .param("since", since)
                .query((rs, _) -> counts.put(rs.getObject("id", UUID.class), rs.getInt("touches")))
                .list();
        return counts;
    }

    // The groups the override's effect also runs through: a group's ancestors; a user's groups and theirs.
    private List<UUID> pathOf(String principalType, UUID principalId) {
        return isUser(principalType) ? userGroupStore.getUserGroupIdsForUser(principalId).stream().sorted().toList() : userGroupStore.ancestorsOf(principalId);
    }

    private boolean inherited(List<UUID> path, Permission permission) {
        return path.stream().anyMatch(group -> liveGrants.getPermissions("USER_GROUP", group).contains(permission));
    }

    private static boolean isUser(String principalType) {
        return "USER".equals(principalType);
    }
}

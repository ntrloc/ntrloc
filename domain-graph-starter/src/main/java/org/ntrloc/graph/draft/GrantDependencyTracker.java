package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.authorization.GrantsApi;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// What a draft grant override depends on in live, and whether live has moved underneath it since
// (docs/ntrloc-system-model-notes.md §13.3-13.4). Draft work: it only reads live, and live never calls it.
@Component
public class GrantDependencyTracker {

    /** Why an override needs reconciling. `id`/`name`: the user or group concerned, where there is one. */
    public record Reason(Kind kind, UUID id, String name) {}

    public enum Kind {
        /** The override's user or group has been deleted. */
        PRINCIPAL_DELETED,
        /** What it grants on (an item type, marker, property, ...) has been deleted. */
        TARGET_DELETED,
        /** The user or group itself changed: its groups, parent, members, subgroups, name or admin flag. */
        PRINCIPAL_CHANGED,
        /** A group the effect runs through changed, or the principal's groups now include it. */
        GROUP_CHANGED,
        /** A group the effect ran through has been deleted. */
        GROUP_DELETED,
        /** Live now gives the permission through the principal's groups, or no longer does. */
        INHERITED_CHANGED
    }

    private final UserStore userStore;
    private final GrantStoreReader liveGrants;
    private final GrantsApi grantsApi;

    public GrantDependencyTracker(UserStore userStore, GrantStoreReader liveGrants, GrantsApi grantsApi) {
        this.userStore = userStore;
        this.liveGrants = liveGrants;
        this.grantsApi = grantsApi;
    }

    public GrantDependencies capture(String principalType, UUID principalId, Permission permission) {
        List<UUID> path = pathOf(principalType, principalId);
        return new GrantDependencies(revisionOf(principalType, List.of(principalId)), path, inherited(path, permission));
    }

    /** For each override, why it needs reconciling with live as it is now; overrides that don't are left out. */
    public Map<DraftGrantOverride, List<Reason>> check(Collection<DraftGrantOverride> overrides) {
        if (overrides.isEmpty()) return Map.of();
        var targets = grantsApi.targets();
        Map<DraftGrantOverride, List<Reason>> found = new LinkedHashMap<>();
        for (var o : overrides) {
            List<Reason> reasons = check(o, targets);
            if (!reasons.isEmpty()) found.put(o, reasons);
        }
        return found;
    }

    private List<Reason> check(DraftGrantOverride o, GrantsApi.GrantTargets targets) {
        Long now = revisionOf(o.principalType(), List.of(o.principalId())).get(o.principalId());
        if (now == null) return List.of(new Reason(Kind.PRINCIPAL_DELETED, o.principalId(), null));
        if (!grantsApi.isGrantable(o.principalType(), o.principalId(), o.permission(), targets)) return List.of(new Reason(Kind.TARGET_DELETED, null, null));

        var recorded = o.dependsOn();
        List<Reason> reasons = new ArrayList<>();
        List<UUID> path = pathOf(o.principalType(), o.principalId());
        if (!now.equals(recorded.principal().get(o.principalId()))) {
            // Moved, or its members or groups changed: that covers any change to the path, too.
            reasons.add(new Reason(Kind.PRINCIPAL_CHANGED, o.principalId(), null));
        } else if (!path.equals(recorded.path())) {
            reasons.addAll(pathChanges(o.principalType(), recorded.path(), path));
        }
        if (inherited(path, o.permission()) != recorded.inherited()) reasons.add(new Reason(Kind.INHERITED_CHANGED, null, null));
        return reasons;
    }

    // A group's ancestors: the nearest one still in place that was moved, i.e. the last before the lists
    // part. A user's groups: each group that left or joined the set (a group above one of theirs moved).
    private List<Reason> pathChanges(String principalType, List<UUID> recorded, List<UUID> now) {
        if (!isUser(principalType)) {
            int same = 0;
            while (same < recorded.size() && same < now.size() && recorded.get(same).equals(now.get(same))) same++;
            // At the first link, the group's own parent changed, which its revision already reports.
            return same == 0 ? List.of() : List.of(named(Kind.GROUP_CHANGED, recorded.get(same - 1)));
        }
        List<Reason> reasons = new ArrayList<>();
        for (UUID id : recorded) if (!now.contains(id)) reasons.add(named(Kind.GROUP_CHANGED, id));
        for (UUID id : now) if (!recorded.contains(id)) reasons.add(named(Kind.GROUP_CHANGED, id));
        return reasons;
    }

    private Reason named(Kind kind, UUID groupId) {
        var group = userStore.findUserGroupById(groupId);
        return new Reason(group.isPresent() ? kind : Kind.GROUP_DELETED, groupId, group.map(UserStore.UserGroupRow::name).orElse(null));
    }

    private Map<UUID, Long> revisionOf(String principalType, List<UUID> ids) {
        return isUser(principalType) ? userStore.userRevisions(ids) : userStore.groupRevisions(ids);
    }

    // The groups the override's effect also runs through: a group's ancestors, nearest first; a user's groups, sorted.
    private List<UUID> pathOf(String principalType, UUID principalId) {
        return isUser(principalType) ? userStore.getUserGroupIdsForUser(principalId).stream().sorted().toList() : userStore.ancestorsOf(principalId);
    }

    private boolean inherited(List<UUID> path, Permission permission) {
        return path.stream().anyMatch(group -> liveGrants.getPermissions("USER_GROUP", group).contains(permission));
    }

    private static boolean isUser(String principalType) {
        return "USER".equals(principalType);
    }
}

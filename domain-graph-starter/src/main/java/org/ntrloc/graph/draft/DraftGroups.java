package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.security.repository.UserGroupStore.MembershipRow;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore.UserGroupRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A draft's groups and memberships (docs/ntrloc-drafts-plan.md step 2.4): live's as they are now, with the
 * draft's changes applied. Immutable; DraftGroupStore builds one per draft and replaces it when either side
 * changes.
 */
public final class DraftGroups {

    private final Map<UUID, UserGroupRow> groups = new HashMap<>();
    private final Map<UUID, Set<UUID>> membersByGroup = new HashMap<>();
    private final Map<UUID, Set<UUID>> groupsByUser = new HashMap<>();
    private final Set<UUID> created = new HashSet<>();
    private final Set<UUID> deleted = new HashSet<>();

    DraftGroups(List<UserGroupRow> liveGroups, List<MembershipRow> liveMemberships, List<DraftGroupChange> changes,
                List<DraftMembershipChange> membershipChanges) {
        liveGroups.forEach(g -> groups.put(g.id(), g));
        for (var c : changes) {
            if (c.created()) {
                created.add(c.groupId());
                groups.put(c.groupId(), new UserGroupRow(c.groupId(), c.name(), c.parentId()));
            } else if (c.deleted()) {
                deleted.add(c.groupId());
                groups.remove(c.groupId());
            } else {
                groups.computeIfPresent(c.groupId(), (id, live) -> new UserGroupRow(id,
                        c.name() != null ? c.name() : live.name(), c.parentId() != null ? c.parentId() : live.parentId()));
            }
        }
        Set<MembershipRow> memberships = new HashSet<>(liveMemberships);
        for (var m : membershipChanges) {
            var row = new MembershipRow(m.userId(), m.groupId());
            if (m.member()) memberships.add(row);
            else memberships.remove(row);
        }
        for (var m : memberships) {
            if (!groups.containsKey(m.groupId())) continue;
            membersByGroup.computeIfAbsent(m.groupId(), k -> new HashSet<>()).add(m.userId());
            groupsByUser.computeIfAbsent(m.userId(), k -> new HashSet<>()).add(m.groupId());
        }
    }

    /** Every group in the draft, by name. */
    public List<UserGroupRow> groups() {
        return groups.values().stream().sorted(Comparator.comparing(UserGroupRow::name)).toList();
    }

    public Optional<UserGroupRow> find(UUID groupId) {
        return Optional.ofNullable(groups.get(groupId));
    }

    public Optional<UserGroupRow> findByName(String name) {
        return groups.values().stream().filter(g -> g.name().equals(name)).findFirst();
    }

    public boolean isRoot(UUID groupId) {
        return find(groupId).map(g -> g.parentId() == null).orElse(false);
    }

    /** The draft makes this group. */
    public boolean creates(UUID groupId) {
        return created.contains(groupId);
    }

    /** The draft removes this live group. */
    public boolean deletes(UUID groupId) {
        return deleted.contains(groupId);
    }

    public Set<UUID> created() {
        return Set.copyOf(created);
    }

    public Set<UUID> members(UUID groupId) {
        return Set.copyOf(membersByGroup.getOrDefault(groupId, Set.of()));
    }

    public boolean isMember(UUID userId, UUID groupId) {
        return groupsByUser.getOrDefault(userId, Set.of()).contains(groupId);
    }

    /** The user's own groups, by name. */
    public List<UserGroupRow> groupsOf(UUID userId) {
        return groupsByUser.getOrDefault(userId, Set.of()).stream().map(groups::get)
                .sorted(Comparator.comparing(UserGroupRow::name)).toList();
    }

    /** The user's groups and every group above them, as the principal's groups are in live. */
    public Set<UUID> effectiveGroupIds(UUID userId) {
        Set<UUID> effective = new LinkedHashSet<>();
        for (UUID group : groupsByUser.getOrDefault(userId, Set.of())) {
            effective.add(group);
            effective.addAll(ancestorsOf(group));
        }
        return Set.copyOf(effective);
    }

    /** Every group above this one, nearest first; it stops at a parent the draft doesn't have. */
    public List<UUID> ancestorsOf(UUID groupId) {
        List<UUID> ancestors = new ArrayList<>();
        UserGroupRow group = groups.get(groupId);
        while (group != null && group.parentId() != null && !ancestors.contains(group.parentId())) {
            ancestors.add(group.parentId());
            group = groups.get(group.parentId());
        }
        return ancestors;
    }

    /** The group and every group below it, by depth, the group first. Each once, even in a cycle live's moves made. */
    public List<UUID> subtreeOf(UUID groupId) {
        if (!groups.containsKey(groupId)) return List.of();
        Map<UUID, List<UUID>> children = new HashMap<>();
        groups.values().forEach(g -> {
            if (g.parentId() != null) children.computeIfAbsent(g.parentId(), k -> new ArrayList<>()).add(g.id());
        });
        Set<UUID> subtree = new LinkedHashSet<>(List.of(groupId));
        List<UUID> queue = new ArrayList<>(subtree);
        for (int i = 0; i < queue.size(); i++) {
            for (UUID child : children.getOrDefault(queue.get(i), List.of())) {
                if (subtree.add(child)) queue.add(child);
            }
        }
        return queue;
    }
}

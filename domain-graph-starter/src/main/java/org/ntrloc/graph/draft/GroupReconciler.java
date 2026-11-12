package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore.UserGroupRow;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// Which of a draft's group and membership changes live has put in question (docs/ntrloc-system-model-notes.md
// §13.4.1): conflicts, where both sides changed the same thing from the same start; and changes no longer
// valid against live as it is now. Draft work: it reads live and the draft, and writes nothing.
@Component
public class GroupReconciler {

    public enum Kind {
        /** Both sides changed the field from the same start, to different values. */
        CONFLICT,
        /** Live deleted the group the change is about. */
        GROUP_GONE,
        /** Live deleted the user the membership is about. */
        USER_GONE,
        /** Live deleted the parent the draft puts the group under. */
        PARENT_GONE,
        /** With live's moves, the draft's would put the group under itself. */
        CYCLE,
        /** Another group has the name the draft gives. */
        NAME_TAKEN,
        /** Live renamed, moved or changed the members of a group the draft deletes. */
        CHANGED_IN_LIVE,
        /** Live put new subgroups under a group the draft deletes. */
        NEW_SUBGROUPS
    }

    public enum Resolution {
        /** Keep draft change: it now starts from live as it is. */
        KEEP,
        /** Preserve live change: the draft drops its change (or, for a delete, brings the group back). */
        USE_LIVE,
        /** Abandon draft change: for a change that can't stand as it is. */
        ABANDON,
        /** For NEW_SUBGROUPS: the draft deletes them too. */
        DELETE_SUBGROUPS
    }

    /** A group, or (for a name) just the name. */
    public record Side(UUID id, String name) {}

    /**
     * Why a change needs reconciling. `field`: name, parent, created or deleted, or null for the change as a
     * whole. `draft`, `live`, `base`: the draft's value, live's now, and live's when the draft set it, where
     * they apply. `groups`: the new subgroups (NEW_SUBGROUPS). `liveChanges`: the live changes behind it,
     * shown here instead of as notices.
     */
    public record Reason(Kind kind, String field, Side draft, Side live, Side base, List<Side> groups,
                         List<Resolution> choices, List<DraftNotice> liveChanges) {}

    /** A reason, and which notices (by element key) it stands in for. */
    record Found(UUID groupId, UUID userId, Reason reason, Predicate<String> covers) {}

    private static final List<Resolution> KEEP_OR_LIVE = List.of(Resolution.KEEP, Resolution.USE_LIVE);
    private static final List<Resolution> LIVE_ONLY = List.of(Resolution.USE_LIVE);
    private static final List<Resolution> ABANDON_ONLY = List.of(Resolution.ABANDON);

    private final UserGroupStore liveGroups;
    private final UserStore liveUsers;
    private final JdbcClient jdbcClient;

    public GroupReconciler(UserGroupStore liveGroups, UserStore liveUsers, JdbcClient jdbcClient) {
        this.liveGroups = liveGroups;
        this.liveUsers = liveUsers;
        this.jdbcClient = jdbcClient;
    }

    /** Everything in the draft's group and membership changes that needs reconciling; `since`: the draft's start. */
    List<Found> check(DraftGroups draft, List<DraftGroupChange> changes, List<DraftMembershipChange> memberships, long since) {
        Map<UUID, UserGroupRow> live = liveGroups.listUserGroups().stream().collect(Collectors.toMap(UserGroupRow::id, Function.identity()));
        Names names = new Names(live, draft);
        List<Found> found = new ArrayList<>();
        for (var c : changes) {
            if (c.created()) checkCreated(c, draft, names, found);
            else if (c.deleted()) checkDeleted(c, draft, live, since, found);
            else checkChanged(c, draft, live.get(c.groupId()), names, found);
        }
        checkMemberships(memberships, draft, live, found);
        return found;
    }

    private void checkCreated(DraftGroupChange c, DraftGroups draft, Names names, List<Found> found) {
        UUID g = c.groupId();
        if (draft.find(c.parentId()).isEmpty()) {
            found.add(new Found(g, null, reason(Kind.PARENT_GONE, "created", names.of(c.parentId()), null, null, ABANDON_ONLY), group(c.parentId())));
        }
        nameTaken(g, c.name(), draft).ifPresent(other ->
                found.add(new Found(g, null, reason(Kind.NAME_TAKEN, "created", new Side(null, c.name()), side(other), null, ABANDON_ONLY), group(other.id()))));
    }

    private void checkDeleted(DraftGroupChange c, DraftGroups draft, Map<UUID, UserGroupRow> live, long since, List<Found> found) {
        UUID g = c.groupId();
        if (!live.containsKey(g)) return;
        if (touches(g, since) > c.deletedTouches()) {
            found.add(new Found(g, null, reason(Kind.CHANGED_IN_LIVE, "deleted", null, side(live.get(g)), null, KEEP_OR_LIVE),
                    key -> key.equals("group:" + g) || key.startsWith("membership:" + g + ":")));
        }
        List<UserGroupRow> subgroups = draft.groups().stream().filter(sub -> g.equals(sub.parentId())).toList();
        if (!subgroups.isEmpty()) {
            Set<String> keys = subgroups.stream().map(sub -> "group:" + sub.id()).collect(Collectors.toSet());
            found.add(new Found(g, null, new Reason(Kind.NEW_SUBGROUPS, "deleted", null, side(live.get(g)), null,
                    subgroups.stream().map(GroupReconciler::side).toList(), List.of(Resolution.DELETE_SUBGROUPS, Resolution.USE_LIVE), List.of()), keys::contains));
        }
    }

    private void checkChanged(DraftGroupChange c, DraftGroups draft, UserGroupRow live, Names names, List<Found> found) {
        UUID g = c.groupId();
        if (live == null) {
            found.add(new Found(g, null, reason(Kind.GROUP_GONE, null, null, null, null, LIVE_ONLY), group(g)));
            return;
        }
        if (c.name() != null && !c.name().equals(live.name())) {
            if (!live.name().equals(c.baseName())) {
                found.add(new Found(g, null, reason(Kind.CONFLICT, "name", new Side(null, c.name()), new Side(null, live.name()),
                        new Side(null, c.baseName()), KEEP_OR_LIVE), group(g)));
            } else {
                nameTaken(g, c.name(), draft).ifPresent(other ->
                        found.add(new Found(g, null, reason(Kind.NAME_TAKEN, "name", new Side(null, c.name()), side(other), null, ABANDON_ONLY), group(other.id()))));
            }
        }
        if (c.parentId() != null && !c.parentId().equals(live.parentId())) {
            if (draft.find(c.parentId()).isEmpty()) {
                found.add(new Found(g, null, reason(Kind.PARENT_GONE, "parent", names.of(c.parentId()), names.of(live.parentId()), null, ABANDON_ONLY),
                        group(c.parentId())));
            } else if (draft.ancestorsOf(g).contains(g)) {
                Set<String> keys = draft.ancestorsOf(g).stream().map(id -> "group:" + id).collect(Collectors.toSet());
                found.add(new Found(g, null, reason(Kind.CYCLE, "parent", names.of(c.parentId()), names.of(live.parentId()), null, ABANDON_ONLY), keys::contains));
            } else if (!live.parentId().equals(c.baseParentId())) {
                found.add(new Found(g, null, reason(Kind.CONFLICT, "parent", names.of(c.parentId()), names.of(live.parentId()),
                        names.of(c.baseParentId()), KEEP_OR_LIVE), group(g)));
            }
        }
    }

    // Removing a member from a group or user live deleted is already done; adding one can't be.
    private void checkMemberships(List<DraftMembershipChange> memberships, DraftGroups draft, Map<UUID, UserGroupRow> live, List<Found> found) {
        List<DraftMembershipChange> adding = memberships.stream().filter(DraftMembershipChange::member).filter(m -> !draft.deletes(m.groupId())).toList();
        if (adding.isEmpty()) return;
        Set<UUID> users = liveUsers.findUsersByIds(adding.stream().map(DraftMembershipChange::userId).collect(Collectors.toSet())).stream()
                .map(UserStore.UserRow::id).collect(Collectors.toSet());
        for (var m : adding) {
            if (!draft.creates(m.groupId()) && !live.containsKey(m.groupId())) {
                found.add(new Found(m.groupId(), m.userId(), reason(Kind.GROUP_GONE, null, null, null, null, LIVE_ONLY), group(m.groupId())));
            } else if (!users.contains(m.userId())) {
                found.add(new Found(m.groupId(), m.userId(), reason(Kind.USER_GONE, null, null, null, null, LIVE_ONLY), ("user:" + m.userId())::equals));
            }
        }
    }

    /** How many of live's changes to the group (renamed, moved, its members) there have been since `since`. */
    int touches(UUID groupId, long since) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM config_change_log
                WHERE seq > :since AND refs @> ARRAY[CAST(:groupId AS uuid)]
                  AND ((kind IN ('group.renamed', 'group.moved') AND element_key = :groupKey)
                    OR (kind IN ('membership.added', 'membership.removed') AND element_key LIKE :membershipKeys))
                """)
                .param("since", since).param("groupId", groupId)
                .param("groupKey", "group:" + groupId).param("membershipKeys", "membership:" + groupId + ":%")
                .query(Integer.class).single();
    }

    private static java.util.Optional<UserGroupRow> nameTaken(UUID groupId, String name, DraftGroups draft) {
        return draft.groups().stream().filter(other -> other.name().equals(name) && !other.id().equals(groupId)).findFirst();
    }

    private static Reason reason(Kind kind, String field, Side draft, Side live, Side base, List<Resolution> choices) {
        return new Reason(kind, field, draft, live, base, List.of(), choices, List.of());
    }

    private static Predicate<String> group(UUID groupId) {
        return ("group:" + groupId)::equals;
    }

    private static Side side(UserGroupRow g) {
        return new Side(g.id(), g.name());
    }

    // A group's name wherever it's found: live, the draft, or live's record of deleting it.
    private final class Names {
        private final Map<UUID, UserGroupRow> live;
        private final DraftGroups draft;
        private final Map<UUID, String> deleted = new HashMap<>();

        Names(Map<UUID, UserGroupRow> live, DraftGroups draft) {
            this.live = live;
            this.draft = draft;
        }

        Side of(UUID id) {
            if (id == null) return null;
            String name = draft.find(id).map(UserGroupRow::name)
                    .orElseGet(() -> live.containsKey(id) ? live.get(id).name() : deleted.computeIfAbsent(id, GroupReconciler.this::deletedName));
            return new Side(id, name);
        }
    }

    private String deletedName(UUID groupId) {
        return jdbcClient.sql("""
                SELECT before ->> 'name' FROM config_change_log WHERE element_key = :key AND kind = 'group.deleted' ORDER BY seq DESC LIMIT 1
                """).param("key", "group:" + groupId).query(String.class).optional().orElse(null);
    }
}

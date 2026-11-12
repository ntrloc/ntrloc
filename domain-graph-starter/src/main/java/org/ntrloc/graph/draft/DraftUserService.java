package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.security.UserService;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore.UserGroupRow;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// UserService's counterpart inside a draft (docs/ntrloc-drafts-plan.md step 2.4): groups and memberships
// changed in the draft, under live's rules applied to the draft's groups (a unique name, a parent that's
// there, no cycles, "everyone" stays), each change in the draft's changelog. The only writer of
// DraftGroupStore. Live is read, never written, until apply, which goes through UserService.
@Service
public class DraftUserService {

    /**
     * A group change as it stands against live now: fields live has caught up with are left out (null).
     * `reconcile`: why live has put it in question (step 2.5); empty if it hasn't.
     */
    public record GroupChange(UUID groupId, boolean created, boolean deleted, String name, String baseName, UUID parentId, UUID baseParentId,
                              List<GroupReconciler.Reason> reconcile) {}

    public record MembershipChange(UUID userId, UUID groupId, boolean member, List<GroupReconciler.Reason> reconcile) {}

    /** The draft's group and membership changes, and the notices of live changes they don't stand in for. */
    public record GroupChanges(List<GroupChange> groups, List<MembershipChange> memberships, List<DraftNotice> notices) {}

    public enum Outcome { APPLIED, ALREADY_LIVE }

    public record AppliedGroupChange(UUID groupId, String change, Outcome outcome) {}

    public record AppliedMembership(UUID userId, UUID groupId, boolean member, Outcome outcome) {}

    private final DraftGroupStore store;
    private final UserGroupStore liveGroups;
    private final UserStore liveUsers;
    private final UserService userService;
    private final DraftAccessService draftAccessService;
    private final DraftChangeLog draftLog;
    private final GroupReconciler reconciler;

    public DraftUserService(DraftGroupStore store, UserGroupStore liveGroups, UserStore liveUsers, UserService userService,
                            DraftAccessService draftAccessService, DraftChangeLog draftLog, GroupReconciler reconciler) {
        this.reconciler = reconciler;
        this.store = store;
        this.liveGroups = liveGroups;
        this.liveUsers = liveUsers;
        this.userService = userService;
        this.draftAccessService = draftAccessService;
        this.draftLog = draftLog;
    }

    public DraftGroups groups(UUID draftId) {
        return store.view(draftId);
    }

    // --- Changes in the draft. Each reads the draft's groups before writing, never after. ---

    @Transactional
    public UserGroupRow createGroup(DraftScope scope, String name, UUID parentId) {
        DraftGroups groups = open(scope);
        String trimmed = requireName(name);
        if (parentId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A parent group is required -- 'everyone' is the only top-level group");
        requireGroup(groups, parentId, "Parent group not found");
        requireNameFree(groups, trimmed, null);
        UUID id = store.create(scope.draftId(), trimmed, parentId);
        draftLog.append(scope.draftId(), "group.created", "group:" + id, List.of(id, parentId), null,
                Map.of("name", trimmed, "parentId", parentId, "parentName", groups.find(parentId).orElseThrow().name()));
        return new UserGroupRow(id, trimmed, parentId);
    }

    @Transactional
    public UserGroupRow renameGroup(DraftScope scope, UUID groupId, String name) {
        return settling(scope, () -> rename(scope, groupId, name));
    }

    private UserGroupRow rename(DraftScope scope, UUID groupId, String name) {
        DraftGroups groups = open(scope);
        UserGroupRow group = requireGroup(groups, groupId, "UserGroup not found");
        String trimmed = requireName(name);
        if (trimmed.equals(group.name())) return group;
        requireNameFree(groups, trimmed, groupId);
        store.setName(scope.draftId(), groupId, trimmed, groups.creates(groupId) ? null : liveGroup(groupId).name());
        draftLog.append(scope.draftId(), "group.renamed", "group:" + groupId, List.of(groupId), Map.of("name", group.name()), Map.of("name", trimmed));
        return new UserGroupRow(groupId, trimmed, group.parentId());
    }

    @Transactional
    public UserGroupRow moveGroup(DraftScope scope, UUID groupId, UUID parentId) {
        return settling(scope, () -> move(scope, groupId, parentId));
    }

    private UserGroupRow move(DraftScope scope, UUID groupId, UUID parentId) {
        DraftGroups groups = open(scope);
        UserGroupRow group = requireGroup(groups, groupId, "UserGroup not found");
        if (parentId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A parent group is required -- 'everyone' is the only top-level group");
        if (group.parentId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'everyone' has no parent");
        UserGroupRow parent = requireGroup(groups, parentId, "Parent group not found");
        if (groupId.equals(parentId) || groups.ancestorsOf(parentId).contains(groupId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A group can't be moved under itself or one of its own subgroups");
        }
        if (parentId.equals(group.parentId())) return group;
        store.setParent(scope.draftId(), groupId, parentId, groups.creates(groupId) ? null : liveGroup(groupId).parentId());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("parentId", group.parentId());
        before.put("parentName", groups.find(group.parentId()).map(UserGroupRow::name).orElse(null));
        draftLog.append(scope.draftId(), "group.moved", "group:" + groupId, List.of(groupId, parentId, group.parentId()), before,
                Map.of("name", group.name(), "parentId", parentId, "parentName", parent.name()));
        return new UserGroupRow(groupId, group.name(), parentId);
    }

    /**
     * Removes the group in the draft. As in live: not "everyone", and one with subgroups only with force,
     * which removes them too. A group the draft made goes entirely, with the draft's memberships and grants
     * for it; a live one is marked deleted, everything else the draft changed about it kept for a restore.
     */
    @Transactional
    public void deleteGroup(DraftScope scope, UUID groupId, boolean force) {
        settling(scope, () -> {
            DraftGroups groups = open(scope);
            UserGroupRow group = requireGroup(groups, groupId, "UserGroup not found");
            if (group.parentId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete the default group");
            int subgroups = groups.subtreeOf(groupId).size() - 1;
            if (subgroups > 0 && !force) throw new ResponseStatusException(HttpStatus.CONFLICT, new UserGroupStore.HasSubgroupsException(subgroups).getMessage());
            deleteSubtree(scope, groups, groupId);
            return null;
        });
    }

    // The group and everything below it in the draft, deepest first.
    private void deleteSubtree(DraftScope scope, DraftGroups groups, UUID groupId) {
        List<UUID> subtree = new ArrayList<>(groups.subtreeOf(groupId));
        java.util.Collections.reverse(subtree);
        for (UUID id : subtree) {
            UserGroupRow deleting = groups.find(id).orElseThrow();
            store.delete(scope.draftId(), id, groups.creates(id) ? 0 : reconciler.touches(id, scope.draft().changelogPosition()));
            if (groups.creates(id)) draftAccessService.dropGrants(scope.draftId(), id);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("name", deleting.name());
            before.put("parentId", deleting.parentId());
            before.put("parentName", groups.find(deleting.parentId()).map(UserGroupRow::name).orElse(null));
            draftLog.append(scope.draftId(), "group.deleted", "group:" + id, List.of(id, deleting.parentId()), before, null);
        }
    }

    /** Brings back a live group the draft deleted. Its parent must be there in the draft, and its name free. */
    @Transactional
    public UserGroupRow restoreGroup(DraftScope scope, UUID groupId) {
        return settling(scope, () -> restore(scope, groupId));
    }

    private UserGroupRow restore(DraftScope scope, UUID groupId) {
        DraftGroups groups = open(scope);
        if (!groups.deletes(groupId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The draft doesn't delete this group");
        var change = store.groupChanges(scope.draftId()).stream().filter(c -> c.groupId().equals(groupId)).findFirst().orElseThrow();
        var live = liveGroups.findUserGroupById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Live has deleted this group too"));
        var restored = new UserGroupRow(groupId, change.name() != null ? change.name() : live.name(), change.parentId() != null ? change.parentId() : live.parentId());
        if (restored.parentId() != null && groups.find(restored.parentId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Its parent group is deleted in the draft: restore that first");
        }
        requireNameFree(groups, restored.name(), groupId);
        store.restore(scope.draftId(), groupId);
        draftLog.append(scope.draftId(), "group.restored", "group:" + groupId, List.of(groupId), null, Map.of("name", restored.name()));
        return restored;
    }

    @Transactional
    public void addMember(DraftScope scope, UUID userId, UUID groupId) {
        setMembership(scope, userId, groupId, true);
    }

    @Transactional
    public void removeMember(DraftScope scope, UUID userId, UUID groupId) {
        setMembership(scope, userId, groupId, false);
    }

    private void setMembership(DraftScope scope, UUID userId, UUID groupId, boolean member) {
        settling(scope, () -> {
            writeMembership(scope, userId, groupId, member);
            return null;
        });
    }

    private void writeMembership(DraftScope scope, UUID userId, UUID groupId, boolean member) {
        DraftGroups groups = open(scope);
        UserGroupRow group = requireGroup(groups, groupId, "UserGroup not found");
        var user = liveUsers.findUserById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (groups.isMember(userId, groupId) == member) return;
        boolean liveMember = !groups.creates(groupId) && liveGroups.getUserGroupsForUser(userId).stream().anyMatch(g -> g.id().equals(groupId));
        store.setMembership(scope.draftId(), userId, groupId, member, liveMember);
        Map<String, Object> membership = Map.of("userId", userId, "userName", user.displayName(), "groupId", groupId, "groupName", group.name());
        draftLog.append(scope.draftId(), member ? "membership.added" : "membership.removed", "membership:" + groupId + ":" + userId,
                List.of(userId, groupId), member ? null : membership, member ? membership : null);
    }

    private DraftGroups open(DraftScope scope) {
        if (!store.holdOpen(scope.draftId())) throw new DraftService.DraftNotFoundException();
        return store.view(scope.draftId());
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UserGroup name is required");
        return name.trim();
    }

    private static UserGroupRow requireGroup(DraftGroups groups, UUID groupId, String notFound) {
        return groups.find(groupId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, notFound));
    }

    private static void requireNameFree(DraftGroups groups, String name, UUID except) {
        groups.findByName(name).filter(g -> !g.id().equals(except)).ifPresent(g -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "UserGroup already exists: " + name);
        });
    }

    private UserGroupRow liveGroup(UUID groupId) {
        return liveGroups.findUserGroupById(groupId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UserGroup not found"));
    }

    // --- What the draft changes, against live now ---

    /**
     * The draft's group and membership changes that still differ from live, each with why it needs
     * reconciling (docs/ntrloc-system-model-notes.md §13.4.1); and the notices the reasons don't stand in for.
     */
    public GroupChanges changes(DraftScope scope) {
        UUID draftId = scope.draftId();
        DraftGroups groups = store.view(draftId);
        List<DraftNotice> notices = draftAccessService.notices(scope);
        List<GroupReconciler.Found> found = check(groups, draftId, scope.draft().changelogPosition());
        Map<List<UUID>, List<GroupReconciler.Reason>> reasons = new LinkedHashMap<>();
        for (var f : found) {
            var r = f.reason();
            var shown = new GroupReconciler.Reason(r.kind(), r.field(), r.draft(), r.live(), r.base(), r.groups(), r.choices(),
                    notices.stream().filter(n -> f.covers().test(n.latest().elementKey())).toList());
            reasons.computeIfAbsent(java.util.Arrays.asList(f.groupId(), f.userId()), k -> new ArrayList<>()).add(shown);
        }
        List<DraftNotice> uncovered = notices.stream().filter(n -> found.stream().noneMatch(f -> f.covers().test(n.latest().elementKey()))).toList();
        return new GroupChanges(groupChanges(draftId, reasons), membershipChanges(groups, draftId, reasons), uncovered);
    }

    private List<GroupChange> groupChanges(UUID draftId, Map<List<UUID>, List<GroupReconciler.Reason>> reasons) {
        List<GroupChange> changes = new ArrayList<>();
        for (var c : store.groupChanges(draftId)) {
            var reconcile = reasons.getOrDefault(java.util.Arrays.asList(c.groupId(), null), List.of());
            if (c.created()) {
                changes.add(new GroupChange(c.groupId(), true, false, c.name(), null, c.parentId(), null, reconcile));
                continue;
            }
            var live = liveGroups.findUserGroupById(c.groupId());
            if (live.isEmpty()) {
                // Live deleted it: a delete is already done; anything else is for reconciling.
                if (!c.deleted()) changes.add(new GroupChange(c.groupId(), false, false, c.name(), c.baseName(), c.parentId(), c.baseParentId(), reconcile));
                continue;
            }
            boolean renamed = c.name() != null && !c.name().equals(live.get().name());
            boolean moved = c.parentId() != null && !c.parentId().equals(live.get().parentId());
            if (c.deleted() || renamed || moved) {
                changes.add(new GroupChange(c.groupId(), false, c.deleted(), renamed ? c.name() : null, renamed ? c.baseName() : null,
                        moved ? c.parentId() : null, moved ? c.baseParentId() : null, reconcile));
            }
        }
        return changes;
    }

    private List<MembershipChange> membershipChanges(DraftGroups groups, UUID draftId, Map<List<UUID>, List<GroupReconciler.Reason>> reasons) {
        return store.membershipChanges(draftId).stream()
                .filter(m -> !groups.deletes(m.groupId()))
                .filter(m -> groups.creates(m.groupId()) || m.member() != isLiveMember(m.userId(), m.groupId()))
                .map(m -> new MembershipChange(m.userId(), m.groupId(), m.member(),
                        reasons.getOrDefault(java.util.Arrays.asList(m.groupId(), m.userId()), List.of())))
                .toList();
    }

    /** How many group and membership changes need reconciling: checked afresh, for apply's transaction. */
    public int unreconciled(UUID draftId, long since) {
        return check(store.build(draftId), draftId, since).size();
    }

    private List<GroupReconciler.Found> check(DraftGroups groups, UUID draftId, long since) {
        return reconciler.check(groups, store.groupChanges(draftId), store.membershipChanges(draftId), since);
    }

    // --- Reconciling (docs/ntrloc-system-model-notes.md §13.4.1). Editing the change is the third way. ---

    /** Settles what's in question about one field of a group change (`field` null: the change as a whole). */
    @Transactional
    public void reconcileGroup(DraftScope scope, UUID groupId, String field, GroupReconciler.Resolution resolution) {
        settling(scope, () -> {
            DraftGroups groups = open(scope);
            long since = scope.draft().changelogPosition();
            List<GroupReconciler.Reason> open = check(groups, scope.draftId(), since).stream()
                    .filter(f -> f.userId() == null && groupId.equals(f.groupId()) && Objects.equals(field, f.reason().field()))
                    .map(GroupReconciler.Found::reason).toList();
            if (open.stream().noneMatch(r -> r.choices().contains(resolution))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, open.isEmpty() ? "Nothing to reconcile there" : "That isn't one of the choices there");
            }
            UUID draftId = scope.draftId();
            var live = liveGroups.findUserGroupById(groupId);
            switch (resolution) {
                case KEEP -> {
                    switch (field) {
                        case "name" -> store.rebaseName(draftId, groupId, live.orElseThrow().name());
                        case "parent" -> store.rebaseParent(draftId, groupId, live.orElseThrow().parentId());
                        default -> store.rebaseDelete(draftId, groupId, reconciler.touches(groupId, since));
                    }
                }
                case USE_LIVE, ABANDON -> {
                    if (field == null) store.discard(draftId, groupId);
                    else if (field.equals("name")) store.setName(draftId, groupId, live.orElseThrow().name(), live.orElseThrow().name());
                    else if (field.equals("parent")) store.setParent(draftId, groupId, live.orElseThrow().parentId(), live.orElseThrow().parentId());
                    else if (field.equals("created")) deleteSubtree(scope, groups, groupId);
                    else restoreWithAncestors(scope, groups, groupId);
                }
                case DELETE_SUBGROUPS -> open.stream().filter(r -> r.kind() == GroupReconciler.Kind.NEW_SUBGROUPS)
                        .flatMap(r -> r.groups().stream()).forEach(sub -> deleteSubtree(scope, groups, sub.id()));
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("field", field);
            after.put("resolution", resolution.name());
            draftLog.append(draftId, "group.reconciled", "group:" + groupId, List.of(groupId), null, after);
            return null;
        });
    }

    /** Drops a membership change live has orphaned (deleting its user or group): the only way to settle it. */
    @Transactional
    public void reconcileMembership(DraftScope scope, UUID userId, UUID groupId, GroupReconciler.Resolution resolution) {
        settling(scope, () -> {
            DraftGroups groups = open(scope);
            boolean open = check(groups, scope.draftId(), scope.draft().changelogPosition()).stream()
                    .anyMatch(f -> groupId.equals(f.groupId()) && userId.equals(f.userId()) && f.reason().choices().contains(resolution));
            if (!open) throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing to reconcile there with that choice");
            store.discardMembership(scope.draftId(), userId, groupId);
            draftLog.append(scope.draftId(), "membership.reconciled", "membership:" + groupId + ":" + userId, List.of(userId, groupId), null,
                    Map.of("resolution", resolution.name()));
            return null;
        });
    }

    // Brings back a group the draft deletes, and the deleted groups above it, so live's new subgroups have a
    // place: top first.
    private void restoreWithAncestors(DraftScope scope, DraftGroups groups, UUID groupId) {
        Map<UUID, DraftGroupChange> changes = new LinkedHashMap<>();
        store.groupChanges(scope.draftId()).forEach(c -> changes.put(c.groupId(), c));
        List<UserGroupRow> chain = new ArrayList<>();
        for (UUID id = groupId; id != null && groups.deletes(id); ) {
            var change = changes.get(id);
            var live = liveGroup(id);
            var restored = new UserGroupRow(id, change.name() != null ? change.name() : live.name(), change.parentId() != null ? change.parentId() : live.parentId());
            chain.addFirst(restored);
            id = restored.parentId();
        }
        for (var restored : chain) {
            requireNameFree(groups, restored.name(), restored.id());
            store.restore(scope.draftId(), restored.id());
            draftLog.append(scope.draftId(), "group.restored", "group:" + restored.id(), List.of(restored.id()), null, Map.of("name", restored.name()));
        }
    }

    // Runs a change to the draft; live changes it settles are acknowledged with it (§13.4.1: the admin isn't
    // asked twice). Settled: covered by a reason before, by none after.
    private <T> T settling(DraftScope scope, java.util.function.Supplier<T> change) {
        if (!store.holdOpen(scope.draftId())) throw new DraftService.DraftNotFoundException();
        long since = scope.draft().changelogPosition();
        var before = check(store.view(scope.draftId()), scope.draftId(), since);
        T result = change.get();
        if (before.isEmpty()) return result;
        var after = check(store.build(scope.draftId()), scope.draftId(), since);
        for (var notice : draftAccessService.notices(scope)) {
            String key = notice.latest().elementKey();
            if (before.stream().anyMatch(f -> f.covers().test(key)) && after.stream().noneMatch(f -> f.covers().test(key))) {
                draftAccessService.acknowledge(scope, key, notice.latest().seq());
            }
        }
        return result;
    }

    private boolean isLiveMember(UUID userId, UUID groupId) {
        return liveGroups.getUserGroupsForUser(userId).stream().anyMatch(g -> g.id().equals(groupId));
    }

    // --- Apply (DraftService.apply, in its transaction) ---

    /**
     * Writes the draft's renames, new groups and moves to live, in an order that keeps live valid at every
     * step: renames, new groups parents first, then moves by their depth in the draft (each parent already
     * in its final place). Memberships come next, deletions after the grants.
     */
    public List<AppliedGroupChange> applyGroupChanges(UUID draftId) {
        // Names are checked once everything is written (applyDeletes), so renames can swap names or take a
        // deleted group's.
        userService.deferGroupNameCheck();
        DraftGroups groups = store.build(draftId);
        List<DraftGroupChange> changes = store.groupChanges(draftId);
        List<AppliedGroupChange> applied = new ArrayList<>();
        Comparator<DraftGroupChange> byDepth = Comparator.comparingInt(c -> groups.ancestorsOf(c.groupId()).size());
        for (var c : changes) {
            if (c.created() || c.deleted() || c.name() == null) continue;
            boolean already = liveGroups.findUserGroupById(c.groupId()).map(g -> g.name().equals(c.name())).orElse(false);
            if (!already) userService.renameUserGroup(c.groupId(), c.name());
            applied.add(new AppliedGroupChange(c.groupId(), "renamed", already ? Outcome.ALREADY_LIVE : Outcome.APPLIED));
        }
        changes.stream().filter(DraftGroupChange::created).sorted(byDepth).forEach(c -> {
            userService.createUserGroup(c.groupId(), c.name(), c.parentId());
            applied.add(new AppliedGroupChange(c.groupId(), "created", Outcome.APPLIED));
        });
        changes.stream().filter(c -> !c.created() && !c.deleted() && c.parentId() != null).sorted(byDepth).forEach(c -> {
            boolean already = liveGroups.findUserGroupById(c.groupId()).map(g -> Objects.equals(g.parentId(), c.parentId())).orElse(false);
            if (!already) userService.setParent(c.groupId(), c.parentId());
            applied.add(new AppliedGroupChange(c.groupId(), "moved", already ? Outcome.ALREADY_LIVE : Outcome.APPLIED));
        });
        return applied;
    }

    /** Writes the draft's memberships to live, after its groups (applyGroupChanges); none for a group it deletes. */
    public List<AppliedMembership> applyMemberships(UUID draftId) {
        DraftGroups groups = store.build(draftId);
        List<AppliedMembership> applied = new ArrayList<>();
        for (var m : store.membershipChanges(draftId)) {
            if (groups.deletes(m.groupId())) continue;
            boolean already = m.member() == isLiveMember(m.userId(), m.groupId());
            if (!already && m.member()) userService.addMember(m.userId(), m.groupId());
            if (!already && !m.member()) userService.removeMember(m.userId(), m.groupId());
            applied.add(new AppliedMembership(m.userId(), m.groupId(), m.member(), already ? Outcome.ALREADY_LIVE : Outcome.APPLIED));
        }
        return applied;
    }

    /** The groups the draft deletes, deepest first, after everything else has been applied. */
    public List<AppliedGroupChange> applyDeletes(UUID draftId) {
        List<AppliedGroupChange> applied = new ArrayList<>();
        store.groupChanges(draftId).stream().filter(DraftGroupChange::deleted)
                .sorted(Comparator.comparingInt((DraftGroupChange c) -> liveGroups.ancestorsOf(c.groupId()).size()).reversed())
                .forEach(c -> {
                    boolean already = liveGroups.findUserGroupById(c.groupId()).isEmpty();
                    if (!already) {
                        try {
                            userService.deleteUserGroup(c.groupId(), false);
                        } catch (UserGroupStore.HasSubgroupsException e) {
                            throw new DraftService.DraftConflictException("A group the draft deletes has subgroups in live that the draft doesn't delete");
                        }
                    }
                    applied.add(new AppliedGroupChange(c.groupId(), "deleted", already ? Outcome.ALREADY_LIVE : Outcome.APPLIED));
                });
        userService.checkGroupNames();
        return applied;
    }

    /** The live groups the draft deletes, as live has them, with their live member counts. */
    public List<Map.Entry<UserGroupRow, Integer>> deletedLiveGroups(UUID draftId) {
        DraftGroups groups = store.view(draftId);
        return liveGroups.listUserGroups().stream().filter(g -> groups.deletes(g.id()))
                .map(g -> Map.entry(g, liveGroups.listUserGroupMembers(g.id()).size())).toList();
    }

    /** The groups the draft deletes (apply leaves their grant changes out). */
    public java.util.Set<UUID> deletedGroups(UUID draftId) {
        return store.groupChanges(draftId).stream().filter(DraftGroupChange::deleted).map(DraftGroupChange::groupId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** Called as the draft closes, in the same transaction. */
    public void draftClosed(UUID draftId) {
        store.forget(draftId);
    }
}

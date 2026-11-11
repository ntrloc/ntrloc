package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.authorization.repository.GrantsView;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.authorization.repository.UserGrantsView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// AccessService's counterpart inside a draft: the only writer of DraftGrantStore. Phase 1 drafts grants
// only; markers stay live.
@Service
public class DraftAccessService {

    /** A grant the draft changes, against live as it is now, and why it needs reconciling (empty if it doesn't). */
    public record GrantChange(DraftGrantOverride override, List<GrantDependencyTracker.Reason> reconcile) {}

    private final DraftGrantStore draftGrantStore;
    private final GrantDependencyTracker dependencies;
    private final DraftNoticeStore notices;
    private final DraftChangeLog draftLog;

    public DraftAccessService(DraftGrantStore draftGrantStore, GrantDependencyTracker dependencies, DraftNoticeStore notices,
                              DraftChangeLog draftLog) {
        this.draftGrantStore = draftGrantStore;
        this.dependencies = dependencies;
        this.notices = notices;
        this.draftLog = draftLog;
    }

    /**
     * Live changes the draft hasn't acknowledged (docs/ntrloc-system-model-notes.md §13.6): informational, so
     * never in apply's way. One per element: its latest change, and how many there were.
     */
    public List<DraftNotice> notices(DraftScope scope) {
        return notices.open(scope.draftId(), scope.draft().changelogPosition());
    }

    /** Acknowledges one element's live changes, up to the entry seen; recorded in the draft's log. */
    @Transactional
    public void acknowledge(DraftScope scope, String elementKey, long seq) {
        if (!draftGrantStore.holdOpen(scope.draftId())) throw new DraftService.DraftNotFoundException();
        notices.acknowledge(scope.draftId(), elementKey, seq, scope.user().id());
        draftLog.append(scope.draftId(), "notice.acknowledged", elementKey, List.of(), null, java.util.Map.of("seq", seq));
    }

    public GrantsView getGrantsView(DraftScope scope) {
        return draftGrantStore.getGrantsView(scope.draftId());
    }

    /** As AccessService.getUserGrants, from the draft's grants: the scope's user, as they are in the draft. */
    public UserGrantsView getUserGrants(DraftScope scope) {
        var user = scope.user();
        return user.isAdmin() ? UserGrantsView.forAdmin() : getGrantsView(scope).forUser(user.id(), user.groupIds());
    }

    public List<DraftGrantOverride> overrides(UUID draftId) {
        return draftGrantStore.overrides(draftId);
    }

    /**
     * What the draft changes in grants, against live as it is now, each with why it needs reconciling with
     * live (docs/ntrloc-system-model-notes.md §13.4). Changes live has caught up with aren't listed.
     */
    public List<GrantChange> changes(UUID draftId) {
        List<DraftGrantOverride> changes = draftGrantStore.changes(draftId);
        var reconcile = dependencies.check(changes);
        return changes.stream().map(o -> new GrantChange(o, reconcile.getOrDefault(o, List.of()))).toList();
    }

    /** The changes that need reconciling before the draft can be applied. */
    public List<GrantChange> unreconciled(UUID draftId) {
        return changes(draftId).stream().filter(c -> !c.reconcile().isEmpty()).toList();
    }

    public enum Resolution { KEEP, USE_LIVE }

    /**
     * Reconciles one change: KEEP it, now depending on live as it is; or USE_LIVE, so the draft no longer
     * changes it. Adjusting it instead is setPermission, which also records live as it is now.
     */
    @Transactional
    public void reconcile(DraftScope scope, String principalType, UUID principalId, Permission permission, Resolution resolution) {
        if (!draftGrantStore.holdOpen(scope.draftId())) throw new DraftService.DraftNotFoundException();
        switch (resolution) {
            case KEEP -> draftGrantStore.rebase(scope.draftId(), principalType, principalId, permission,
                    dependencies.capture(principalType, principalId, permission, scope.draft().changelogPosition()));
            case USE_LIVE -> draftGrantStore.discard(scope.draftId(), principalType, principalId, permission);
        }
    }

    /** As AccessService.setPermission, in the draft: live is untouched. Not once the draft has closed. */
    @Transactional
    public void setPermission(DraftScope scope, String principalType, UUID principalId, Permission permission, boolean value) {
        if (!draftGrantStore.holdOpen(scope.draftId())) throw new DraftService.DraftNotFoundException();
        draftGrantStore.setPermission(scope.draftId(), principalType, principalId, permission, value,
                dependencies.capture(principalType, principalId, permission, scope.draft().changelogPosition()));
    }

    /** Called as the draft closes, in the same transaction. */
    public void draftClosed(UUID draftId) {
        draftGrantStore.forget(draftId);
    }
}

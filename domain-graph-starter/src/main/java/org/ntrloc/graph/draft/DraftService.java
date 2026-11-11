package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.authorization.AccessService;
import org.ntrloc.graph.db.partition.authorization.GrantsApi;
import org.ntrloc.graph.db.partition.changelog.ChangeLog;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.draft.DraftRecord.Status;
import org.ntrloc.graph.principal.NtrlocPrincipal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// The draft registry (docs/ntrloc-system-model-notes.md §11): admins start, list and discard drafts and
// invite non-admins into them. Admins reach every draft and are never invited; a non-admin reaches only
// the drafts they're invited to. Phase 1 allows one open draft at a time. Applying writes a draft's
// changes to live through the services that own them; the draft keeps its overrides as the record.
@Service
public class DraftService {

    /** The request can't be carried out as things stand (e.g. a draft is already open). */
    public static class DraftConflictException extends RuntimeException {
        DraftConflictException(String message) {
            super(message);
        }
    }

    /** No such draft, or none this user may see. */
    public static class DraftNotFoundException extends RuntimeException {
        DraftNotFoundException() {
            super("No such draft");
        }
    }

    /** What applying did with each of a draft's grant overrides. */
    public enum GrantOutcome {
        /** Live changed to the draft's value. */
        APPLIED,
        /** Live had the draft's value already. */
        ALREADY_LIVE
    }

    public record AppliedGrant(DraftGrantOverride override, GrantOutcome outcome) {}

    public record AppliedDraft(DraftRecord draft, List<AppliedGrant> grants) {}

    private final DraftStore draftStore;
    private final UserStore userStore;
    private final DraftAccessService draftAccessService;
    private final AccessService accessService;
    private final GrantStoreReader liveGrants;
    private final GrantsApi grantsApi;
    private final ChangeLog changeLog;
    private final DraftChangeLog draftLog;

    public DraftService(DraftStore draftStore, UserStore userStore, DraftAccessService draftAccessService, AccessService accessService,
                        GrantStoreReader liveGrants, GrantsApi grantsApi, ChangeLog changeLog, DraftChangeLog draftLog) {
        this.changeLog = changeLog;
        this.draftLog = draftLog;
        this.draftStore = draftStore;
        this.userStore = userStore;
        this.draftAccessService = draftAccessService;
        this.accessService = accessService;
        this.liveGrants = liveGrants;
        this.grantsApi = grantsApi;
    }

    /** The open drafts this user can work in. */
    public List<DraftRecord> openDrafts(NtrlocPrincipal user) {
        return user.isAdmin() ? draftStore.listOpen() : draftStore.listOpenFor(user.id());
    }

    /** An open draft this user can work in. */
    public DraftRecord openDraft(UUID draftId, NtrlocPrincipal user) {
        var draft = draftStore.find(draftId).filter(d -> d.status() == Status.OPEN).orElseThrow(DraftNotFoundException::new);
        if (!user.isAdmin() && !draftStore.isParticipant(draftId, user.id())) throw new DraftNotFoundException();
        return draft;
    }

    @Transactional
    public DraftRecord start(String name, NtrlocPrincipal admin) {
        requireAdmin(admin);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A draft needs a name");
        draftStore.lockForCreate();
        draftStore.listOpen().stream().findFirst().ifPresent(open -> {
            throw new DraftConflictException("\"" + open.name() + "\" is still open: apply or discard it first");
        });
        var draft = draftStore.create(name.trim(), admin.id(), changeLog.latestPosition());
        draftLog.append(draft.id(), "draft.started", "draft:" + draft.id(), List.of(draft.id()), null, Map.of("name", draft.name()));
        return draft;
    }

    @Transactional
    public DraftRecord discard(UUID draftId, NtrlocPrincipal admin) {
        requireAdmin(admin);
        var closed = draftStore.close(draftId, Status.DISCARDED, admin.id()).orElseThrow(DraftNotFoundException::new);
        draftAccessService.draftClosed(draftId);
        draftLog.append(draftId, "draft.discarded", "draft:" + draftId, List.of(draftId), null, Map.of("name", closed.name()));
        return closed;
    }

    /**
     * Writes the draft's changes to live and closes it, all or nothing (docs/ntrloc-system-model-notes.md
     * §7.2.1). A permission is on or off, so live can't hold a third value that conflicts: it's either
     * still at the override's base, or already at the draft's value.
     */
    @Transactional
    public AppliedDraft apply(UUID draftId, NtrlocPrincipal admin) {
        requireAdmin(admin);
        var draft = draftStore.lockOpen(draftId).orElseThrow(DraftNotFoundException::new);
        // Checked here, inside apply's transaction, where it's current (docs/ntrloc-system-model-notes.md §13.4).
        int unreconciled = draftAccessService.unreconciled(draftId).size();
        if (unreconciled > 0) {
            throw new DraftConflictException(unreconciled == 1
                    ? "1 change needs reconciling with live before the draft can be applied"
                    : unreconciled + " changes need reconciling with live before the draft can be applied");
        }
        var targets = grantsApi.targets();
        List<AppliedGrant> grants = new ArrayList<>();
        try {
            // Live's changelog entries for these writes say they came from the draft.
            ChangeLog.withSource("draft '" + draft.name() + "'", () -> {
                for (var override : draftAccessService.overrides(draftId)) {
                    grants.add(new AppliedGrant(override, applyGrant(override, targets)));
                }
            });
        } catch (DataIntegrityViolationException e) {
            throw new DraftConflictException("Live changed while the draft was being applied: try again");
        }
        var closed = draftStore.close(draftId, Status.APPLIED, admin.id()).orElseThrow(DraftNotFoundException::new);
        draftAccessService.draftClosed(draftId);
        draftLog.append(draftId, "draft.applied", "draft:" + draftId, List.of(draftId), null, Map.of("name", closed.name(),
                "applied", grants.stream().filter(g -> g.outcome() == GrantOutcome.APPLIED).count(),
                "alreadyLive", grants.stream().filter(g -> g.outcome() == GrantOutcome.ALREADY_LIVE).count()));
        return new AppliedDraft(closed, grants);
    }

    private GrantOutcome applyGrant(DraftGrantOverride override, GrantsApi.GrantTargets targets) {
        // Already live includes a permission turned off on something live has since deleted: nothing to do.
        boolean live = liveGrants.getPermissions(override.principalType(), override.principalId()).contains(override.permission());
        if (live == override.value()) return GrantOutcome.ALREADY_LIVE;
        // Only if live deleted it after the check above: nothing is applied, and a retry reports it.
        if (!grantsApi.isGrantable(override.principalType(), override.principalId(), override.permission(), targets)) {
            throw new DraftConflictException("Live changed while the draft was being applied: try again");
        }
        accessService.setPermission(override.principalType(), override.principalId(), override.permission(), override.value());
        return GrantOutcome.APPLIED;
    }

    public List<DraftParticipantRecord> participants(UUID draftId, NtrlocPrincipal admin) {
        requireAdmin(admin);
        openDraft(draftId, admin);
        return draftStore.participants(draftId);
    }

    @Transactional
    public void invite(UUID draftId, UUID userId, NtrlocPrincipal admin) {
        requireAdmin(admin);
        openDraft(draftId, admin);
        var user = userStore.findUserById(userId).orElseThrow(() -> new IllegalArgumentException("No such user"));
        if (user.isAdmin()) throw new IllegalArgumentException("Admins reach every draft; there's no need to invite them");
        if (draftStore.isParticipant(draftId, userId)) return;
        draftStore.addParticipant(draftId, userId);
        draftLog.append(draftId, "participant.invited", "participant:" + userId, List.of(userId), null,
                Map.of("userId", userId, "userName", user.displayName()));
    }

    @Transactional
    public void uninvite(UUID draftId, UUID userId, NtrlocPrincipal admin) {
        requireAdmin(admin);
        openDraft(draftId, admin);
        if (!draftStore.isParticipant(draftId, userId)) return;
        draftStore.removeParticipant(draftId, userId);
        String name = userStore.findUserById(userId).map(UserStore.UserRow::displayName).orElse(null);
        Map<String, Object> participant = new java.util.LinkedHashMap<>();
        participant.put("userId", userId);
        participant.put("userName", name);
        draftLog.append(draftId, "participant.removed", "participant:" + userId, List.of(userId), participant, null);
    }

    private static void requireAdmin(NtrlocPrincipal user) {
        if (!user.isAdmin()) throw new DraftAccessDeniedException();
    }

    /** Only admins manage drafts. */
    public static class DraftAccessDeniedException extends RuntimeException {
        DraftAccessDeniedException() {
            super("Only admins can manage drafts");
        }
    }
}

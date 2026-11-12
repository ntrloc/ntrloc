package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.authorization.GrantsApi;
import org.ntrloc.graph.db.partition.changelog.ChangeEntries;
import org.ntrloc.graph.db.partition.authorization.GrantsApi.PrincipalRef;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.principal.ExternalPrincipalResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// The draft registry's API (docs/ntrloc-drafts-plan.md step 1.3). Anyone signed in may list the drafts
// they can work in; everything else is for admins.
@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    public record UserRef(UUID id, String displayName) {}

    public record DraftView(UUID id, String name, String status, UserRef createdBy, OffsetDateTime createdAt) {}

    public record CreateDraftRequest(String name) {}

    public record ParticipantView(UUID id, String displayName, String email, OffsetDateTime addedAt) {}

    public record AddParticipantRequest(UUID userId) {}

    public record AppliedGrantView(PrincipalRef principal, Permission permission, boolean value, String outcome) {}

    public record AppliedDraftView(DraftView draft, List<DraftUserService.AppliedGroupChange> groups,
                                   List<DraftUserService.AppliedMembership> memberships, List<AppliedGrantView> grants) {}

    /** `reconcile`: why live has moved underneath this change since it was made; empty if it hasn't. */
    public record GrantChangeView(PrincipalRef principal, Permission permission, boolean value, List<GrantDependencyTracker.Reason> reconcile) {}

    /**
     * One item to settle, and how. A grant: principal and permission (KEEP or USE_LIVE). A group change:
     * groupId and field (name, parent, created, deleted; none for the change as a whole). A membership:
     * groupId and userId. Each reason lists its choices.
     */
    public record ReconcileRequest(PrincipalRef principal, Permission permission, UUID groupId, String field, UUID userId,
                                   GroupReconciler.Resolution resolution) {}

    /**
     * What the draft changes against live, by family; and the live changes it has yet to acknowledge, less
     * those a group or membership change's reasons show instead.
     */
    public record DraftChangesView(List<DraftUserService.GroupChange> groups, List<DraftUserService.MembershipChange> memberships,
                                   List<GrantChangeView> grants, List<DraftNotice> notices) {}

    public record AcknowledgeRequest(String elementKey, Long seq) {}

    private final DraftService draftService;
    private final DraftAccessService draftAccessService;
    private final DraftChangeLog draftLog;
    private final UserStore userStore;
    private final ExternalPrincipalResolver principalResolver;
    private final DraftUserService draftUserService;

    public DraftController(DraftService draftService, DraftAccessService draftAccessService, DraftChangeLog draftLog, UserStore userStore,
                           ExternalPrincipalResolver principalResolver, DraftUserService draftUserService) {
        this.draftUserService = draftUserService;
        this.draftLog = draftLog;
        this.draftService = draftService;
        this.draftAccessService = draftAccessService;
        this.userStore = userStore;
        this.principalResolver = principalResolver;
    }

    @GetMapping
    List<DraftView> list(Authentication authentication) {
        return draftService.openDrafts(principalResolver.resolve(authentication)).stream().map(this::view).toList();
    }

    @PostMapping
    ResponseEntity<DraftView> create(@RequestBody CreateDraftRequest body, Authentication authentication) {
        var draft = draftService.start(body.name(), principalResolver.resolve(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(draft));
    }

    @GetMapping("/{draftId}")
    DraftView get(DraftScope scope) {
        return view(scope.draft());
    }

    @PostMapping("/{draftId}/discard")
    DraftView discard(@PathVariable UUID draftId, Authentication authentication) {
        return view(draftService.discard(draftId, principalResolver.resolve(authentication)));
    }

    @PostMapping("/{draftId}/apply")
    AppliedDraftView apply(@PathVariable UUID draftId, Authentication authentication) {
        var applied = draftService.apply(draftId, principalResolver.resolve(authentication));
        return new AppliedDraftView(view(applied.draft()), applied.groups(), applied.memberships(), applied.grants().stream().map(g -> {
            var o = g.override();
            return new AppliedGrantView(principalOf(o), o.permission(), o.value(), g.outcome().name());
        }).toList());
    }

    // Group, membership and grant changes are shown in the Access view, which is for admins.
    @GetMapping("/{draftId}/changes")
    DraftChangesView changes(DraftScope scope) {
        GrantsApi.requireAdmin(scope.user());
        var groups = draftUserService.changes(scope);
        return new DraftChangesView(groups.groups(), groups.memberships(),
                draftAccessService.changes(scope.draftId()).stream()
                .map(c -> new GrantChangeView(principalOf(c.override()), c.override().permission(), c.override().value(), c.reconcile())).toList(),
                groups.notices());
    }

    // Acknowledging a live change the draft was told about (docs/ntrloc-system-model-notes.md §13.6), admins only.
    @PostMapping("/{draftId}/acknowledge")
    ResponseEntity<Void> acknowledge(DraftScope scope, @RequestBody AcknowledgeRequest body) {
        GrantsApi.requireAdmin(scope.user());
        if (body.elementKey() == null || body.seq() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "elementKey and seq are required");
        }
        draftAccessService.acknowledge(scope, body.elementKey(), body.seq());
        return ResponseEntity.noContent().build();
    }

    // What has happened in the draft, oldest first (docs/ntrloc-system-model-notes.md §13.6), admins only.
    @GetMapping("/{draftId}/log")
    List<ChangeEntries.Entry> log(DraftScope scope) {
        GrantsApi.requireAdmin(scope.user());
        return draftLog.entries(scope.draftId());
    }

    // Reconciling a change with live (docs/ntrloc-system-model-notes.md §13.4), admins only.
    @PostMapping("/{draftId}/reconcile")
    ResponseEntity<Void> reconcile(DraftScope scope, @RequestBody ReconcileRequest body) {
        GrantsApi.requireAdmin(scope.user());
        if (body.resolution() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resolution is required");
        if (body.groupId() != null && body.userId() != null) {
            draftUserService.reconcileMembership(scope, body.userId(), body.groupId(), body.resolution());
        } else if (body.groupId() != null) {
            draftUserService.reconcileGroup(scope, body.groupId(), body.field(), body.resolution());
        } else {
            if (body.principal() == null || body.permission() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "principal and permission, or groupId, are required");
            }
            var resolution = switch (body.resolution()) {
                case KEEP -> DraftAccessService.Resolution.KEEP;
                case USE_LIVE -> DraftAccessService.Resolution.USE_LIVE;
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A grant change is kept, or live's value taken");
            };
            draftAccessService.reconcile(scope, GrantsApi.principalType(body.principal()), body.principal().id(), body.permission(), resolution);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{draftId}/participants")
    List<ParticipantView> participants(@PathVariable UUID draftId, Authentication authentication) {
        return draftService.participants(draftId, principalResolver.resolve(authentication)).stream()
                .map(p -> new ParticipantView(p.userId(), p.displayName(), p.email(), p.addedAt()))
                .toList();
    }

    @PostMapping("/{draftId}/participants")
    ResponseEntity<Void> invite(@PathVariable UUID draftId, @RequestBody AddParticipantRequest body, Authentication authentication) {
        if (body.userId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        draftService.invite(draftId, body.userId(), principalResolver.resolve(authentication));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{draftId}/participants/{userId}")
    ResponseEntity<Void> uninvite(@PathVariable UUID draftId, @PathVariable UUID userId, Authentication authentication) {
        draftService.uninvite(draftId, userId, principalResolver.resolve(authentication));
        return ResponseEntity.noContent().build();
    }

    private static PrincipalRef principalOf(DraftGrantOverride o) {
        return new PrincipalRef("USER".equals(o.principalType()) ? "user" : "group", o.principalId());
    }

    private DraftView view(DraftRecord draft) {
        UserRef createdBy = draft.createdBy() == null ? null : userStore.findUserById(draft.createdBy())
                .map(u -> new UserRef(u.id(), u.displayName())).orElse(null);
        return new DraftView(draft.id(), draft.name(), draft.status().name(), createdBy, draft.createdAt());
    }
}

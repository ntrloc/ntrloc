package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.authorization.GrantsApi;
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

    public record AppliedDraftView(DraftView draft, List<AppliedGrantView> grants) {}

    public record GrantChangeView(PrincipalRef principal, Permission permission, boolean value) {}

    /** What the draft changes against live, by family; Phase 1 drafts grants only. */
    public record DraftChangesView(List<GrantChangeView> grants) {}

    private final DraftService draftService;
    private final DraftAccessService draftAccessService;
    private final UserStore userStore;
    private final ExternalPrincipalResolver principalResolver;

    public DraftController(DraftService draftService, DraftAccessService draftAccessService, UserStore userStore,
                           ExternalPrincipalResolver principalResolver) {
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
        return new AppliedDraftView(view(applied.draft()), applied.grants().stream().map(g -> {
            var o = g.override();
            return new AppliedGrantView(principalOf(o), o.permission(), o.value(), g.outcome().name());
        }).toList());
    }

    // Grant changes are shown in the Access view, which is for admins.
    @GetMapping("/{draftId}/changes")
    DraftChangesView changes(DraftScope scope) {
        GrantsApi.requireAdmin(scope.user());
        return new DraftChangesView(draftAccessService.changes(scope).stream()
                .map(o -> new GrantChangeView(principalOf(o), o.permission(), o.value())).toList());
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

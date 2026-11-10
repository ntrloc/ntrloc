package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.authorization.GrantsApi;
import org.ntrloc.graph.db.partition.authorization.GrantsApi.GrantTargets;
import org.ntrloc.graph.db.partition.authorization.GrantsApi.PrincipalPermissions;
import org.ntrloc.graph.db.partition.authorization.GrantsApi.SetPermissionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// The grants API inside a draft: the same shapes as GrantsAdminController, at
// /api/drafts/{draftId}/admin/grants, reading and writing the draft's grants. What can be granted (the
// schema, the markers) isn't drafted in Phase 1, so targets are always live's.
@RestController
@RequestMapping("/api/drafts/{draftId}/admin/grants")
public class DraftGrantsAdminController {

    private final GrantsApi grantsApi;
    private final DraftAccessService draftAccessService;

    public DraftGrantsAdminController(GrantsApi grantsApi, DraftAccessService draftAccessService) {
        this.grantsApi = grantsApi;
        this.draftAccessService = draftAccessService;
    }

    @GetMapping
    List<PrincipalPermissions> getPermissions(DraftScope scope, @RequestParam("principal") List<String> principals) {
        GrantsApi.requireAdmin(scope.user());
        return grantsApi.permissions(draftAccessService.getGrantsView(scope), principals);
    }

    @PutMapping
    ResponseEntity<Void> setPermission(DraftScope scope, @RequestBody SetPermissionRequest body) {
        GrantsApi.requireAdmin(scope.user());
        grantsApi.requireValid(body);
        draftAccessService.setPermission(scope, GrantsApi.principalType(body.principal()), body.principal().id(), body.permission(), body.value());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/targets")
    GrantTargets getTargets(DraftScope scope) {
        GrantsApi.requireAdmin(scope.user());
        return grantsApi.targets();
    }
}

package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.db.partition.authorization.GrantsApi.GrantTargets;
import org.ntrloc.graph.db.partition.authorization.GrantsApi.PrincipalPermissions;
import org.ntrloc.graph.db.partition.authorization.GrantsApi.SetPermissionRequest;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.principal.ExternalPrincipalResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// The grants API, live: a principal's permissions read and set one at a time (docs/ntrloc-drafts-plan.md,
// "1.0 decided"). Each permission is named as Permission names it, which is also how a draft records an
// override. The fine-grained endpoints in AccessAdminController stay for the legacy /admin screen.
@RestController
@RequestMapping("/api/admin/grants")
public class GrantsAdminController {

    private final GrantsApi grantsApi;
    private final GrantStoreReader grantReader;
    private final AccessService accessService;
    private final ExternalPrincipalResolver principalResolver;

    public GrantsAdminController(GrantsApi grantsApi, GrantStoreReader grantReader, AccessService accessService,
                                 ExternalPrincipalResolver principalResolver) {
        this.grantsApi = grantsApi;
        this.grantReader = grantReader;
        this.accessService = accessService;
        this.principalResolver = principalResolver;
    }

    @GetMapping
    List<PrincipalPermissions> getPermissions(@RequestParam("principal") List<String> principals, Authentication authentication) {
        GrantsApi.requireAdmin(principalResolver.resolve(authentication));
        return grantsApi.permissions(grantReader.getGrantsView(), principals);
    }

    @PutMapping
    ResponseEntity<Void> setPermission(@RequestBody SetPermissionRequest body, Authentication authentication) {
        GrantsApi.requireAdmin(principalResolver.resolve(authentication));
        grantsApi.requireValid(body);
        try {
            accessService.setPermission(GrantsApi.principalType(body.principal()), body.principal().id(), body.permission(), body.value());
        } catch (DataIntegrityViolationException e) {
            // deleted since requireValid looked
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The permission names something that doesn't exist");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/targets")
    GrantTargets getTargets(Authentication authentication) {
        GrantsApi.requireAdmin(principalResolver.resolve(authentication));
        return grantsApi.targets();
    }
}

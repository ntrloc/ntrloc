package org.ntrloc.graph.db.partition.changelog;

import org.ntrloc.graph.principal.ExternalPrincipalResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

// The Operations screen's API (docs/ntrloc-drafts-plan.md step 2.2), admins only. First item: how long the
// live changelog keeps entries (docs/ntrloc-system-model-notes.md §13.6).
@RestController
@RequestMapping("/api/admin/operations")
public class OperationsAdminController {

    /** retentionDays: null keeps everything. entries/oldest: what the log holds now. */
    public record ChangeLogView(Integer retentionDays, long entries, OffsetDateTime oldest) {}

    public record ChangeLogSettings(Integer retentionDays) {}

    private final ChangeLogRetention retention;
    private final ExternalPrincipalResolver principalResolver;

    public OperationsAdminController(ChangeLogRetention retention, ExternalPrincipalResolver principalResolver) {
        this.retention = retention;
        this.principalResolver = principalResolver;
    }

    @GetMapping("/changelog")
    ChangeLogView changeLog(Authentication authentication) {
        requireAdmin(authentication);
        return view();
    }

    // Saving prunes at once, so the screen shows the new retention's effect; after that, hourly.
    @PutMapping("/changelog")
    ChangeLogView setChangeLog(@RequestBody ChangeLogSettings body, Authentication authentication) {
        requireAdmin(authentication);
        try {
            retention.setRetentionDays(body.retentionDays());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        retention.prune();
        return view();
    }

    private ChangeLogView view() {
        var size = retention.size();
        return new ChangeLogView(retention.retentionDays().orElse(null), size.entries(), size.oldest());
    }

    private void requireAdmin(Authentication authentication) {
        if (!principalResolver.resolve(authentication).isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can manage operations");
        }
    }
}

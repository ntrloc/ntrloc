package org.ntrloc.graph.draft;

import jakarta.servlet.http.HttpServletRequest;
import org.ntrloc.graph.db.EntityReadService;
import org.ntrloc.graph.projection.CollectionProjectionSpec;
import org.ntrloc.graph.projection.ItemProjectionSpec;
import org.ntrloc.graph.projection.ProjectedItem;
import org.ntrloc.graph.projection.ProjectionController;
import org.ntrloc.graph.projection.ProjectionResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The projection API inside a draft: live's items, read with the user's draft grants. Items aren't
// drafted in Phase 1, so only what the user may see changes.
@RestController
@RequestMapping("/api/drafts/{draftId}/projection")
public class DraftProjectionController {

    private final EntityReadService entityReadService;
    private final DraftAccessService draftAccessService;

    public DraftProjectionController(EntityReadService entityReadService, DraftAccessService draftAccessService) {
        this.entityReadService = entityReadService;
        this.draftAccessService = draftAccessService;
    }

    @PostMapping("/collection")
    ResponseEntity<ProjectionResult> collection(DraftScope scope, @RequestBody CollectionProjectionSpec spec, HttpServletRequest request) {
        return ResponseEntity.ok(entityReadService.project(spec, ProjectionController.baseUrl(request), draftAccessService.getUserGrants(scope)));
    }

    @PostMapping("/item")
    ResponseEntity<ProjectedItem> item(DraftScope scope, @RequestBody ItemProjectionSpec spec, HttpServletRequest request) {
        return entityReadService.project(spec, ProjectionController.baseUrl(request), draftAccessService.getUserGrants(scope))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

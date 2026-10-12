package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.access.Pipeline;
import org.ntrloc.graph.access.PipelineLayout;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerDecisionPipelineRepository;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerDecisionPipelineRepository.PipelineRow;
import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Marker decision pipeline CRUD -- the pipeline-model admin surface, successor to the retired
// MarkerAdminController rule endpoints (/api/admin/markers/rules). Raw JSON for now: a caller
// submits/receives the full org.ntrloc.graph.access.Pipeline graph as-is, relying on its own
// sealed-interface Jackson annotations to (de)serialize through WebFlux's codecs -- there's no
// visual editor yet (see MarkerDecisionPipelineRepository's own comment), so this is script/API
// authoring only until one exists.
//
// No name anywhere here -- a pipeline is identified by the item type it belongs to (one-to-one, see
// the repository's own comment), not by an admin-chosen label.
@RestController
@RequestMapping("/api/admin/marker-decision-pipelines")
public class MarkerDecisionPipelineAdminController {

    public record PipelineView(UUID id, UUID itemTypeId, boolean enabled, Pipeline pipeline, PipelineLayout layout) {}

    // layout is nullable -- a caller authoring raw JSON (no editor yet) shouldn't have to spell out
    // an empty one; absent means "no layout yet" and is normalized to empty positions below.
    public record CreatePipelineRequest(UUID itemTypeId, Pipeline pipeline, PipelineLayout layout) {}

    public record UpdatePipelineRequest(Pipeline pipeline, boolean enabled, PipelineLayout layout) {}

    private final MarkerDecisionPipelineRepository repository;
    private final PrincipalResolver principalResolver;

    public MarkerDecisionPipelineAdminController(MarkerDecisionPipelineRepository repository, PrincipalResolver principalResolver) {
        this.repository = repository;
        this.principalResolver = principalResolver;
    }

    @GetMapping
    List<PipelineView> listPipelines(ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        return repository.getAllPipelines().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    PipelineView getPipeline(@PathVariable UUID id, ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        return repository.findById(id).map(this::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such pipeline"));
    }

    // The admin UI's primary lookup -- an item type has at most one pipeline, so this is the
    // surface it actually uses, rather than fetching the full list and filtering client-side.
    @GetMapping("/by-item-type/{itemTypeId}")
    PipelineView getPipelineForItemType(@PathVariable UUID itemTypeId, ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        return repository.findByItemTypeId(itemTypeId).map(this::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No pipeline for this item type"));
    }

    @PostMapping
    PipelineView createPipeline(@RequestBody CreatePipelineRequest body, ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        if (body.itemTypeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "itemTypeId is required");
        }
        if (body.pipeline() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pipeline is required");
        }
        try {
            return toView(repository.createPipeline(body.itemTypeId(), body.pipeline(), orEmpty(body.layout())));
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This item type already has a pipeline", e);
        }
    }

    @PutMapping("/{id}")
    PipelineView updatePipeline(@PathVariable UUID id, @RequestBody UpdatePipelineRequest body,
                                 ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        if (body.pipeline() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pipeline is required");
        }
        return toView(repository.updatePipeline(id, body.pipeline(), body.enabled(), orEmpty(body.layout())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePipeline(@PathVariable UUID id, ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        repository.deletePipeline(id);
    }

    private PipelineView toView(PipelineRow row) {
        return new PipelineView(row.id(), row.itemTypeId(), row.enabled(), row.pipeline(), row.layout());
    }

    private PipelineLayout orEmpty(PipelineLayout layout) {
        return layout != null ? layout : new PipelineLayout(Map.of(), Map.of());
    }

    private void requireAdmin(ServerHttpRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(request, authentication);
        if (!principal.isSuperuser()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can manage marker decision pipelines");
        }
    }
}

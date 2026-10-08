package org.ntrloc.graph.db.partition.authorization;

import org.ntrloc.graph.access.Pipeline;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerDecisionPipelineRepository;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerDecisionPipelineRepository.PipelineRow;
import org.ntrloc.graph.db.partition.security.PrincipalResolver;
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
import java.util.UUID;

// Marker decision pipeline CRUD -- the pipeline-model admin surface, successor to the retired
// MarkerAdminController rule endpoints (/api/admin/markers/rules). Raw JSON for now: a caller
// submits/receives the full org.ntrloc.graph.access.Pipeline graph as-is, relying on its own
// sealed-interface Jackson annotations to (de)serialize through WebFlux's codecs -- there's no
// visual editor yet (see MarkerDecisionPipelineRepository's own comment), so this is script/API
// authoring only until one exists.
@RestController
@RequestMapping("/api/admin/marker-decision-pipelines")
public class MarkerDecisionPipelineAdminController {

    public record PipelineView(UUID id, String name, UUID itemTypeId, boolean enabled, Pipeline pipeline) {}

    public record CreatePipelineRequest(String name, UUID itemTypeId, Pipeline pipeline) {}

    public record UpdatePipelineRequest(String name, Pipeline pipeline, boolean enabled) {}

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

    @PostMapping
    PipelineView createPipeline(@RequestBody CreatePipelineRequest body, ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (body.itemTypeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "itemTypeId is required");
        }
        if (body.pipeline() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pipeline is required");
        }
        return toView(repository.createPipeline(body.name(), body.itemTypeId(), body.pipeline()));
    }

    @PutMapping("/{id}")
    PipelineView updatePipeline(@PathVariable UUID id, @RequestBody UpdatePipelineRequest body,
                                 ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (body.pipeline() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pipeline is required");
        }
        return toView(repository.updatePipeline(id, body.name(), body.pipeline(), body.enabled()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePipeline(@PathVariable UUID id, ServerHttpRequest request, Authentication authentication) {
        requireAdmin(request, authentication);
        repository.deletePipeline(id);
    }

    private PipelineView toView(PipelineRow row) {
        return new PipelineView(row.id(), row.name(), row.itemTypeId(), row.enabled(), row.pipeline());
    }

    private void requireAdmin(ServerHttpRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(request, authentication);
        if (!principal.isSuperuser()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can manage marker decision pipelines");
        }
    }
}

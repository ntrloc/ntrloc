package org.ntrloc.graph.mutation;

import org.ntrloc.graph.db.EntityWriteService;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.ExternalPrincipalResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/mutation")
public class MutationController {

    // Goes through EntityWriteService, not MutationRequestProcessor directly -- EntityWriteService is now
    // the single entry point for both directions of the entity system (reads via project(),
    // writes via mutate()); MutationRequestProcessor is an implementation detail behind it.
    private final EntityWriteService entityWriteService;
    private final ExternalPrincipalResolver principalResolver;

    public MutationController(EntityWriteService entityWriteService, ExternalPrincipalResolver principalResolver) {
        this.entityWriteService = entityWriteService;
        this.principalResolver = principalResolver;
    }

    @PostMapping
    ResponseEntity<?> mutate(@RequestBody MutationRequest request, Authentication authentication) {
        try {
            // Never returns null (throws 401 instead), which entityWriteService.mutate() demands via
            // @PreAuthorize (see docs/ntrloc-principal-enforcement-plan.md, Part C).
            NtrlocPrincipal principal = principalResolver.resolve(authentication);
            return ResponseEntity.ok(entityWriteService.mutate(request, principal));
        } catch (MutationValidationException e) {
            return ResponseEntity.badRequest().body(new MutationErrorResponse(e.errors()));
        }
    }

    // Confirms a transaction previously submitted with commit=false. transactionId comes back from
    // that earlier call's MutationResponse.
    @PostMapping("/{transactionId}/commit")
    ResponseEntity<?> commit(@PathVariable UUID transactionId) {
        return ResponseEntity.ok(entityWriteService.confirmCommit(transactionId));
    }

    // Discards a transaction previously submitted with commit=false.
    @PostMapping("/{transactionId}/abort")
    ResponseEntity<?> abort(@PathVariable UUID transactionId) {
        return ResponseEntity.ok(entityWriteService.confirmAbort(transactionId));
    }
}

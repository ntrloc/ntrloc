package org.ntrloc.graph.mutation;

import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
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

    // Goes through EntityManager, not MutationRequestProcessor directly -- EntityManager is now
    // the single entry point for both directions of the entity system (reads via project(),
    // writes via mutate()); MutationRequestProcessor is an implementation detail behind it.
    private final EntityManager entityManager;
    private final PrincipalResolver principalResolver;

    public MutationController(EntityManager entityManager, PrincipalResolver principalResolver) {
        this.entityManager = entityManager;
        this.principalResolver = principalResolver;
    }

    @PostMapping
    ResponseEntity<?> mutate(@RequestBody MutationRequest request, HttpServletRequest httpRequest, Authentication authentication) {
        try {
            // Same resolve(request, authentication) ProcessAdminController.startProcessInstance
            // already uses -- real session first, stand-in header/query-param fallback only when
            // security is disabled. Never returns null (the stand-in path throws 401 if
            // unresolvable), which is what entityManager.mutate() now demands via @PreAuthorize
            // (see docs/ntrloc-principal-enforcement-plan.md, Part C).
            NtrlocPrincipal principal = principalResolver.resolve(httpRequest, authentication);
            return ResponseEntity.ok(entityManager.mutate(request, principal));
        } catch (MutationValidationException e) {
            return ResponseEntity.badRequest().body(new MutationErrorResponse(e.errors()));
        }
    }

    // Confirms a transaction previously submitted with commit=false. transactionId comes back from
    // that earlier call's MutationResponse.
    @PostMapping("/{transactionId}/commit")
    ResponseEntity<?> commit(@PathVariable UUID transactionId) {
        return ResponseEntity.ok(entityManager.confirmCommit(transactionId));
    }

    // Discards a transaction previously submitted with commit=false.
    @PostMapping("/{transactionId}/abort")
    ResponseEntity<?> abort(@PathVariable UUID transactionId) {
        return ResponseEntity.ok(entityManager.confirmAbort(transactionId));
    }
}

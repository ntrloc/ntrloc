package org.ntrloc.graph.db.mutation;

import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    ResponseEntity<?> mutate(@RequestBody MutationRequest request, ServerHttpRequest httpRequest, Authentication authentication) {
        try {
            // Same resolve(request, authentication) ProcessAdminController.startProcessInstance
            // already uses -- real session first, stand-in header/query-param fallback only when
            // security is disabled. Purely for ledger attribution here, not a permission gate
            // (EntityManager.mutate's own note).
            NtrlocPrincipal principal = principalResolver.resolve(httpRequest, authentication);
            return ResponseEntity.ok(entityManager.mutate(request, principal));
        } catch (MutationValidationException e) {
            return ResponseEntity.badRequest().body(new MutationErrorResponse(e.errors()));
        }
    }
}

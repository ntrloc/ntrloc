package org.ntrloc.graph.db;

import org.ntrloc.graph.db.partition.security.ExternalPrincipalResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/item/{itemId}")
public class ItemController {

    private final EntityManager entityManager;
    private final ExternalPrincipalResolver principalResolver;

    public ItemController(EntityManager entityManager, ExternalPrincipalResolver principalResolver) {
        this.entityManager = entityManager;
        this.principalResolver = principalResolver;
    }

    // Begin the item's participation in a state machine (enters the START target state).
    @PostMapping("/state-machines/{stateMachineName}/start")
    ResponseEntity<Void> startStateMachine(@PathVariable UUID itemId, @PathVariable String stateMachineName,
                                            Authentication authentication) {
        entityManager.startStateMachine(itemId, stateMachineName, principalResolver.resolve(authentication));
        return ResponseEntity.noContent().build();
    }

    // Advance an active state machine along one outgoing transition (by id).
    @PostMapping("/state-machines/{stateMachineName}/transitions/{transitionId}")
    ResponseEntity<Void> executeTransition(@PathVariable UUID itemId, @PathVariable String stateMachineName,
                                            @PathVariable UUID transitionId,
                                            Authentication authentication) {
        entityManager.executeTransition(itemId, stateMachineName, transitionId, principalResolver.resolve(authentication));
        return ResponseEntity.noContent().build();
    }
}

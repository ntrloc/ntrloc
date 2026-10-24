package org.ntrloc.graph.events;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.TestPrincipals;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// No real scoped event type exists yet (chatMessage is still hypothetical) -- this proves the
// registry mechanism itself: a type with no registered predicate is open to everyone, a type with
// one is actually enforced per-principal, and multiple predicates for the same type must all agree.
class ClusterEventVisibilityRegistryTest {

    private static NtrlocPrincipal principal(UUID id) {
        return TestPrincipals.of(id, "Test User", "test@example.com", Set.of(), false);
    }

    @Test
    void isVisible_forATypeWithNoRegisteredPredicate_isVisibleToEveryone() {
        var registry = new ClusterEventVisibilityRegistry(List.of());
        var event = new ClusterEvent("schemaChanged", "payload");

        assertThat(registry.isVisible(event, principal(UUID.randomUUID()))).isTrue();
    }

    @Test
    void isVisible_forATypeWithARegisteredPredicate_isEnforcedPerPrincipal() {
        UUID allowedPrincipalId = UUID.randomUUID();
        ClusterEventVisibility onlyAllowedPrincipal = new ClusterEventVisibility() {
            @Override
            public boolean supports(String eventType) {
                return "chatMessage".equals(eventType);
            }

            @Override
            public boolean isVisible(ClusterEvent event, NtrlocPrincipal principal) {
                return principal.id().equals(allowedPrincipalId);
            }
        };
        var registry = new ClusterEventVisibilityRegistry(List.of(onlyAllowedPrincipal));
        var event = new ClusterEvent("chatMessage", "conversation-123");

        assertThat(registry.isVisible(event, principal(allowedPrincipalId))).isTrue();
        assertThat(registry.isVisible(event, principal(UUID.randomUUID()))).isFalse();
    }

    @Test
    void isVisible_forAnUnrelatedType_ignoresAPredicateThatDoesNotSupportIt() {
        ClusterEventVisibility chatOnly = new ClusterEventVisibility() {
            @Override
            public boolean supports(String eventType) {
                return "chatMessage".equals(eventType);
            }

            @Override
            public boolean isVisible(ClusterEvent event, NtrlocPrincipal principal) {
                return false;
            }
        };
        var registry = new ClusterEventVisibilityRegistry(List.of(chatOnly));
        var event = new ClusterEvent("schemaChanged", "payload");

        assertThat(registry.isVisible(event, principal(UUID.randomUUID()))).isTrue();
    }

    @Test
    void isVisible_forATypeWithMultiplePredicates_requiresAllOfThemToAgree() {
        ClusterEventVisibility alwaysTrue = supportingPredicate("chatMessage", true);
        ClusterEventVisibility alwaysFalse = supportingPredicate("chatMessage", false);
        var registry = new ClusterEventVisibilityRegistry(List.of(alwaysTrue, alwaysFalse));
        var event = new ClusterEvent("chatMessage", "conversation-123");

        assertThat(registry.isVisible(event, principal(UUID.randomUUID()))).isFalse();
    }

    private static ClusterEventVisibility supportingPredicate(String type, boolean visible) {
        return new ClusterEventVisibility() {
            @Override
            public boolean supports(String eventType) {
                return type.equals(eventType);
            }

            @Override
            public boolean isVisible(ClusterEvent event, NtrlocPrincipal principal) {
                return visible;
            }
        };
    }
}

package org.ntrloc.graph.events;

import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.springframework.stereotype.Component;

import java.util.List;

// Aggregates every ClusterEventVisibility bean on the classpath -- core and any use-case-specific
// module alike, via ordinary Spring dependency injection, no registration mechanism of our own
// needed. A type with no registered predicate is visible to everyone (today's behavior for every
// existing event type, none of which need scoping); a type with one or more predicates must
// satisfy all of them.
@Component
public class ClusterEventVisibilityRegistry {

    private final List<ClusterEventVisibility> predicates;

    public ClusterEventVisibilityRegistry(List<ClusterEventVisibility> predicates) {
        this.predicates = predicates;
    }

    public boolean isVisible(ClusterEvent event, NtrlocPrincipal principal) {
        return predicates.stream()
                .filter(predicate -> predicate.supports(event.type()))
                .allMatch(predicate -> predicate.isVisible(event, principal));
    }
}

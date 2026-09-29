package org.ntrloc.graph.events;

import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;

// Implemented by any component that needs to restrict a specific event *type* to a subset of
// principals -- e.g. a future DAM chat extension restricting chatMessage events to conversation
// participants. Not needed for today's only event types (schemaChanged, taskChanged), which are
// visible to every authenticated user -- ClusterEventVisibilityRegistry treats "no registered
// predicate for this type" as "visible to everyone", so those need no implementation at all.
// An ordinary Spring bean, discovered like any other -- see ClusterEventVisibilityRegistry for how
// this is meant to be picked up automatically, including from a use-case-specific module's own
// package, not just this one.
public interface ClusterEventVisibility {

    boolean supports(String eventType);

    boolean isVisible(ClusterEvent event, NtrlocPrincipal principal);
}

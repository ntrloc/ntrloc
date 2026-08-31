package org.ntrloc.graph.db.projection;

import java.util.UUID;

// A transition the requesting principal may currently execute on an item, surfaced per active
// state machine in ProjectedItemState. toKind is "NORMAL" or "END" so a client can label an
// END-bound transition as "end the workflow". Not filtered by guard -- the execute endpoint
// re-checks the guard authoritatively.
public record AvailableTransition(UUID id, String name, String toState, String toKind) {}

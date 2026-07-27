package org.ntrloc.graph.db.projection;

import org.springframework.lang.Nullable;

// Deliberately narrow -- currentState (name) and currentTransition (name, if one is in flight).
// "Claimed by" and anything else state-machine-internal is left for later; this is what a client
// actually needs to display or filter on, not the full internal state-machine record.
public record ProjectedItemState(String currentState, @Nullable String currentTransition) {}

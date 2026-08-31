package org.ntrloc.graph.db.projection;

import org.springframework.lang.Nullable;

import java.util.List;

// The item's runtime relationship to one state machine on its type.
//  - active:   currentState set; availableTransitions = the outgoing transitions from that state
//              the requesting principal may execute (permission-filtered, not guard-filtered).
//  - inactive: currentState null; startable = whether the principal may begin the machine
//              (state-machine:start via a marker on the item, or superuser). Re-entry after END is
//              valid, so a previously-ended machine looks identical to a never-started one here.
// currentTransition is reserved for the (not-yet-built) in-flight async transition claim.
public record ProjectedItemState(
        @Nullable String currentState,
        @Nullable String currentTransition,
        boolean startable,
        List<AvailableTransition> availableTransitions) {}

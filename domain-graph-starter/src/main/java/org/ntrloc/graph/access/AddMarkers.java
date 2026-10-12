package org.ntrloc.graph.access;

import java.util.List;
import java.util.UUID;

// Adds one or more markers to the carrier (set union). gate.isUnconditional() means this always fires.
// Minimum 1 marker, not 2 like TotalityPredicate/ConditionGroup: adding a single marker is already a
// complete, meaningful operation, not a trivial case of "combining" something.
public record AddMarkers(UUID id, Gate gate, List<UUID> markerIds) implements CarrierBlock {

    public AddMarkers {
        if (id == null) throw new IllegalArgumentException("AddMarkers requires an id");
        gate = gate == null ? Gate.UNCONDITIONAL : gate;
        if (markerIds == null || markerIds.isEmpty()) {
            throw new IllegalArgumentException("AddMarkers requires at least 1 marker");
        }
        markerIds = List.copyOf(markerIds);
    }
}

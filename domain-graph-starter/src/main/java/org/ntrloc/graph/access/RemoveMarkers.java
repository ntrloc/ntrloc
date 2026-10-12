package org.ntrloc.graph.access;

import java.util.List;
import java.util.UUID;

// Same shape as AddMarkers, opposite operation (set difference instead of union). Commutative, same
// as AddMarkers -- safe in any order, from any starting carrier.
public record RemoveMarkers(UUID id, Gate gate, List<UUID> markerIds) implements CarrierBlock {

    public RemoveMarkers {
        if (id == null) throw new IllegalArgumentException("RemoveMarkers requires an id");
        gate = gate == null ? Gate.UNCONDITIONAL : gate;
        if (markerIds == null || markerIds.isEmpty()) {
            throw new IllegalArgumentException("RemoveMarkers requires at least 1 marker");
        }
        markerIds = List.copyOf(markerIds);
    }
}

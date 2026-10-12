package org.ntrloc.graph.access;

import java.util.List;
import java.util.UUID;

// Same shape as AddMarkers/RemoveMarkers, but discards whatever carrier it receives entirely and
// replaces it outright -- simultaneously a sink for its predecessor's carrier and a fresh source for
// whatever follows, functionally a second implicit Start sitting mid-pipeline. Its carrier-in is
// still mandatory for structural completeness (no dangling paths) even though the value received
// never affects the output. No fork/join restriction: that concern assumed a procedural
// execution-order model this architecture never had -- SetMarkers only ever looks at its own single,
// always-well-defined incoming pin, regardless of what any other branch is doing. See "Marker
// actions" in the design notes for the full reasoning.
public record SetMarkers(UUID id, Gate gate, List<UUID> markerIds) implements CarrierBlock {

    public SetMarkers {
        if (id == null) throw new IllegalArgumentException("SetMarkers requires an id");
        gate = gate == null ? Gate.UNCONDITIONAL : gate;
        if (markerIds == null || markerIds.isEmpty()) {
            throw new IllegalArgumentException("SetMarkers requires at least 1 marker");
        }
        markerIds = List.copyOf(markerIds);
    }
}

package org.ntrloc.graph.access;

import java.util.List;
import java.util.UUID;

// Pipeline-level carrier connectivity, kept as a flat list separate from the block definitions
// (which are inherently tree-shaped -- TotalityPredicate/CountedPredicate embed their sub-predicates
// directly). One entry per carrier source: outputPinId is a CarrierBlock's own id (used as its
// carrier-out address), and inputPinIds are the CarrierBlock ids it feeds (each used as a carrier-in
// address). Fan-out (one source, many targets) and fan-in (several sources feeding one target, which
// is just implicit carrier merge) both fall out of this for free -- no combining node needed for
// either. Signal wiring (AddMarkers.gate, Filter.signal, etc.) never goes through Route: a signal
// gate is a direct UUID reference to a Condition's own pin id (whether standalone or nested inside a
// ConditionGroup), resolved by pull, not by a pushed route.
public record Route(UUID outputPinId, List<UUID> inputPinIds) {

    public Route {
        if (outputPinId == null) throw new IllegalArgumentException("Route requires an outputPinId");
        if (inputPinIds == null || inputPinIds.isEmpty()) {
            throw new IllegalArgumentException("Route requires at least 1 inputPinId");
        }
        inputPinIds = List.copyOf(inputPinIds);
    }
}

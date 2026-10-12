package org.ntrloc.graph.access;

import java.util.UUID;

// Structurally distinct from AddMarkers/RemoveMarkers/SetMarkers, not just a variant: no marker ids
// at all (it never touches the carrier's content), and `signal` is mandatory rather than optional --
// a Filter with no signal would just be a meaningless straight wire, so forcing one keeps it from
// existing in a degenerate form, same principle as TotalityPredicate/ConditionGroup requiring 2+.
// Kept named `signal`, not `gate` like every other carrier block's optional point of contact, precisely
// because it isn't optional here -- the name itself signals that distinction.
// Always fires and always produces a carrier -- never conditionally skipped -- passing its carrier
// through unchanged when the signal is on, or producing an empty carrier (same sink-and-become-a-
// source role as SetMarkers, just with an empty marker set) when it's off. No transform either way,
// purely binary. An empty carrier merges as a no-op wherever it ends up, which is how a path
// "terminates without changing anything" with no dedicated terminal/stop-here node needed.
public record Filter(UUID id, Gate signal) implements CarrierBlock {

    public Filter {
        if (id == null) throw new IllegalArgumentException("Filter requires an id");
        if (signal == null || signal.isUnconditional()) {
            throw new IllegalArgumentException("Filter requires a signal");
        }
    }
}

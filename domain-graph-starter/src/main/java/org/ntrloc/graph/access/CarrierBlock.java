package org.ntrloc.graph.access;

// Carrier blocks: pushed into, never pulled. Every one of these carries a real carrier field (or, for
// Start/End, exactly one side of it) and never reads a fact directly -- that's the signal domain's
// job entirely. The only point of contact between the two domains anywhere in this model is a carrier
// block's `gate`/`signal` field (a UUID resolving to a SingleOutputSignal's or PinnedPredicate's pin
// id) -- never the reverse. See "Marker actions: carriers and virtual blocks" in the design notes.
public sealed interface CarrierBlock permits AddMarkers, RemoveMarkers, SetMarkers, Filter, Start, End {

    // Every carrier block's own id doubles as both its carrier-in address (when it appears as a
    // Route target) and its carrier-out address (when it appears as a Route source) -- there's only
    // ever one of each per block, so one id suffices for both roles. Start has no carrier-in and End
    // has no carrier-out, but each still has the one id it actually uses.
    java.util.UUID id();
}

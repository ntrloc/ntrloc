package org.ntrloc.graph.access;

import java.util.List;
import java.util.UUID;

// A real carrier block, not a virtual/sugar one -- it has a genuine carrier input and output of its
// own, pushed into like every other carrier block. Routes one shared carrier input to several
// independently-consequential branches (adds), each an ordinary AddMarkers with its own ordinary gate
// -- nothing special about how a row fires, it's the exact same mechanism any standalone AddMarkers
// already has. Each row's AddMarkers.id() doubles as that row's own carrier output pin, addressable by
// Route the same way any other carrier block's id is (see Pipeline/PipelineEvaluator) -- something
// downstream can wire to NATURE's own output specifically, not just Select's primary one.
//
// id() is this block's own primary pin, both carrier-in and the universal carrier-out every
// CarrierBlock has -- and it doubles as (Otherwise). It is NOT an unconditional passthrough: it's
// gated by `gate`, ordinarily authored as NONE over the same condition pins the rows themselves gate
// on (see Gate/Quantity) -- carrierIn passes through only when none of the rows matched, empty
// otherwise. Nothing here enforces that relationship structurally; `gate` is just an ordinary Gate like
// any other carrier block has, authored by convention to mean "otherwise," not a special case the
// evaluator treats differently.
//
// Every row is fed the same carrier Select itself received -- a broadcast, not something an admin
// wires with an explicit Route (see PipelineEvaluator): nothing ever routes to a row from outside.
public record Select(UUID id, Gate gate, List<AddMarkers> adds) implements CarrierBlock {

    public Select {
        if (id == null) throw new IllegalArgumentException("Select requires an id");
        gate = gate == null ? Gate.UNCONDITIONAL : gate;
        if (adds == null || adds.isEmpty()) {
            throw new IllegalArgumentException("Select requires at least 1 add");
        }
        adds = List.copyOf(adds);
    }
}

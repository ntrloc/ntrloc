package org.ntrloc.graph.access;

import java.util.UUID;

// Exactly one per pipeline, revising an earlier version of this doc that allowed any number -- a
// pure carrier sink (carrier-in, no output), the union of every carrier that ever arrives here. The
// carrier *is* the decision, which only holds unambiguously if there's exactly one place it could be;
// a diagram may still draw this icon more than once for legibility, but every drawn instance refers
// to this same singleton.
public record End(UUID id) implements CarrierBlock {

    public End {
        if (id == null) throw new IllegalArgumentException("End requires an id");
    }
}

package org.ntrloc.graph.access;

import java.util.UUID;

// Exactly one per pipeline -- a pure carrier source (no carrier-in, only out), feeding the first
// action block(s) with the empty marker set.
public record Start(UUID id) implements CarrierBlock {

    public Start {
        if (id == null) throw new IllegalArgumentException("Start requires an id");
    }
}

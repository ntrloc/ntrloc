package org.ntrloc.graph.access;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// Carrier blocks: pushed into, never pulled. Every one of these carries a real carrier field (or, for
// Start/End, exactly one side of it) and never reads a fact directly -- that's the signal domain's
// job entirely. The only point of contact between the two domains anywhere in this model is a carrier
// block's own Gate (AddMarkers/RemoveMarkers/SetMarkers/Select's `gate`, Filter's `signal`) -- a list
// of condition pin ids plus a Quantity, never the reverse. See "Marker actions: carriers and virtual
// blocks" in the design notes.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AddMarkers.class, name = "ADD_MARKERS"),
        @JsonSubTypes.Type(value = RemoveMarkers.class, name = "REMOVE_MARKERS"),
        @JsonSubTypes.Type(value = SetMarkers.class, name = "SET_MARKERS"),
        @JsonSubTypes.Type(value = Filter.class, name = "FILTER"),
        @JsonSubTypes.Type(value = Select.class, name = "SELECT"),
        @JsonSubTypes.Type(value = Start.class, name = "START"),
        @JsonSubTypes.Type(value = End.class, name = "END")
})
public sealed interface CarrierBlock permits AddMarkers, RemoveMarkers, SetMarkers, Filter, Select, Start, End {

    // Every carrier block's own id doubles as both its carrier-in address (when it appears as a
    // Route target) and its carrier-out address (when it appears as a Route source) -- there's only
    // ever one of each per block, so one id suffices for both roles. Start has no carrier-in and End
    // has no carrier-out, but each still has the one id it actually uses. Select is the one exception
    // to "only one of each" -- its own id is still its primary carrier-in/out, but each of its rows
    // (AddMarkers) has an additional carrier-out address of its own; see Select's own comment.
    java.util.UUID id();
}

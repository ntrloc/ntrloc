package org.ntrloc.graph.db.partition.ledger;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

// A marker change always comes from exactly one source -- a person acting directly
// (ManuallyAppliedMarker), an item-type rule (RuleAppliedMarker), or a state's entry marker
// decision (StateAppliedMarker) -- never more than one at a time in a given ledger entry. Modeling
// this as a sealed interface with per-source variants, rather than one record with optional fields,
// makes each source's required fields actually required.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "source")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RuleAppliedMarker.class, name = "RULE"),
        @JsonSubTypes.Type(value = ManuallyAppliedMarker.class, name = "MANUAL"),
        @JsonSubTypes.Type(value = StateAppliedMarker.class, name = "STATE")
})
public sealed interface MarkerAttribution permits RuleAppliedMarker, ManuallyAppliedMarker, StateAppliedMarker {
    UUID markerId();
}

package org.ntrloc.graph.db.partition.ledger;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

// A marker change always comes from exactly one source -- a person acting directly
// (ManuallyAppliedMarker) or the marker decision pipeline (PipelineAppliedMarker) -- never more
// than one at a time in a given ledger entry. Modeling this as a sealed interface with per-source
// variants, rather than one record with optional fields, makes each source's required fields
// actually required.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "source")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ManuallyAppliedMarker.class, name = "MANUAL"),
        @JsonSubTypes.Type(value = PipelineAppliedMarker.class, name = "PIPELINE")
})
public sealed interface MarkerAttribution permits ManuallyAppliedMarker, PipelineAppliedMarker {
    UUID markerId();
}

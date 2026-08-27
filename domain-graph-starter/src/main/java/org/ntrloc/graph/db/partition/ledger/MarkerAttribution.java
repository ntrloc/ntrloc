package org.ntrloc.graph.db.partition.ledger;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

// A marker change always comes from exactly one of two sources -- a rule (RuleAppliedMarker) or a
// person acting directly (ManuallyAppliedMarker) -- never both, never neither. Modeling this as a
// sealed interface with two variants, rather than one record with optional rule/reason fields,
// makes each source's required fields actually required: a rule-applied marker can't exist without
// its ruleId+version, a manual one can't exist without who did it and why.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "source")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RuleAppliedMarker.class, name = "RULE"),
        @JsonSubTypes.Type(value = ManuallyAppliedMarker.class, name = "MANUAL")
})
public sealed interface MarkerAttribution permits RuleAppliedMarker, ManuallyAppliedMarker {
    UUID markerId();
}

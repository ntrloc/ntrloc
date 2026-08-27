package org.ntrloc.graph.db.partition.ledger;

import java.util.UUID;

// ruleId and ruleVersion are both mandatory -- a deployed rule (a Flowable DMN decision, most
// likely) is never known without knowing which version fired, so this variant simply can't exist
// without both. Not yet produced by anything real: the rule engine that would construct these is
// still unbuilt, but the shape needs to exist now so the ledger can carry it once it does.
public record RuleAppliedMarker(UUID markerId, UUID ruleId, int ruleVersion) implements MarkerAttribution {
}

package org.ntrloc.graph.db.partition.ledger;

import java.util.UUID;

// A marker conferred (or revoked) by a marker decision pipeline's evaluation. pipelineId scopes
// the provenance so that a later re-evaluation of the SAME pipeline can tell "is this marker
// currently applied because of me" and safely revoke it when its own carrier blocks no longer
// want it -- a marker held manually, or by a different pipeline, is left alone.
public record PipelineAppliedMarker(UUID markerId, UUID pipelineId) implements MarkerAttribution {
}

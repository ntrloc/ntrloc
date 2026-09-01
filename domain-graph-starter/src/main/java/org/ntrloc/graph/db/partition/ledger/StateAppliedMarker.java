package org.ntrloc.graph.db.partition.ledger;

import java.util.UUID;

// A marker conferred on an item because it entered a specific state of a specific state machine
// (via that state's entry marker decision). stateMachineId + stateId scope the provenance so that
// when the item later leaves that state, only this state's contribution is revoked -- a marker the
// same state re-applies, or one held manually / by an item-type rule / by a state in another
// machine, survives. Produced by StateMarkerDecisionService during ledger prepare.
public record StateAppliedMarker(UUID markerId, UUID stateMachineId, UUID stateId) implements MarkerAttribution {
}

package org.ntrloc.graph.db.partition.ledger;

import java.util.UUID;

// userExternalId matches the same external-id scheme ledger_entry.actor_external_id already uses
// for attribution elsewhere -- not the user's internal id. In the only path that constructs this
// today (MarkerAssignmentService), it will equal the entry's own actor_external_id, since there's
// exactly one actor per call; kept per-marker rather than only read off the entry so a marker's
// attribution stays self-describing even if a future caller ever batches manual assignments from
// different actors into one transaction. reason is mandatory, unlike a rule (whose own definition
// is the explanation) -- a person acting directly has no other record of why.
public record ManuallyAppliedMarker(UUID markerId, String userExternalId, String reason) implements MarkerAttribution {
}

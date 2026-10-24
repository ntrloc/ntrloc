package org.ntrloc.graph.db.partition.register;

import java.time.OffsetDateTime;
import java.util.UUID;

// What every row staged by one prepare() shares: its transaction, who made the change, and when.
public record StagingContext(UUID transactionId, UUID actorUserId, OffsetDateTime createdDate) {
}

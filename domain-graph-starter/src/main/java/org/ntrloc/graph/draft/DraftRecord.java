package org.ntrloc.graph.draft;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One draft in the registry. createdBy/closedBy are null once that user is deleted. */
public record DraftRecord(UUID id, String name, Status status, UUID createdBy, OffsetDateTime createdAt,
                          UUID closedBy, OffsetDateTime closedAt) {

    public enum Status { OPEN, APPLIED, DISCARDED }
}

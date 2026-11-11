package org.ntrloc.graph.draft;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One draft in the registry. createdBy/closedBy are null once that user is deleted. */
// changelogPosition: the live changelog's latest entry when the draft started; live changes after it
// are the draft's to hear about (docs/ntrloc-system-model-notes.md §13.6).
public record DraftRecord(UUID id, String name, Status status, UUID createdBy, OffsetDateTime createdAt,
                          UUID closedBy, OffsetDateTime closedAt, long changelogPosition) {

    public enum Status { OPEN, APPLIED, DISCARDED }
}

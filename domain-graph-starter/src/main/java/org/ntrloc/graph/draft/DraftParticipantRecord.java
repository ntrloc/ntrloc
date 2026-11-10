package org.ntrloc.graph.draft;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A non-admin invited into a draft. */
public record DraftParticipantRecord(UUID userId, String displayName, String email, OffsetDateTime addedAt) {}

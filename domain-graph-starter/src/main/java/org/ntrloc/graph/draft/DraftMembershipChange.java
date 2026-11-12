package org.ntrloc.graph.draft;

import java.util.UUID;

/** One membership a draft changes: whether the user is in the group in the draft, and in live when it was set. */
public record DraftMembershipChange(UUID userId, UUID groupId, boolean member, boolean baseMember) {}

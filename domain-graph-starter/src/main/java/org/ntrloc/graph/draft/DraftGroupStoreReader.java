package org.ntrloc.graph.draft;

import java.util.List;
import java.util.UUID;

/** Reading a draft's groups, for draft code other than their one writer (DraftUserService). */
public interface DraftGroupStoreReader {

    /** The draft's groups and memberships: live's now, with the draft's changes applied. */
    DraftGroups view(UUID draftId);

    List<DraftGroupChange> groupChanges(UUID draftId);

    List<DraftMembershipChange> membershipChanges(UUID draftId);
}

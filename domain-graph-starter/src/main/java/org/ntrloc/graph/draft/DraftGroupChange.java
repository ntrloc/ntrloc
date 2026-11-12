package org.ntrloc.graph.draft;

import java.util.UUID;

/**
 * One group a draft changes (draft_group_change). A group the draft creates has its name and parent and no
 * bases. For a live group, name and parentId are what the draft sets (null = unchanged), and the bases are
 * live's values when they were set.
 */
public record DraftGroupChange(UUID groupId, boolean created, boolean deleted, String name, String baseName, UUID parentId,
                               UUID baseParentId) {}

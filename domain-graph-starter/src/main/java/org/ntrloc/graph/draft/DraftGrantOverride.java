package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.authorization.repository.Permission;

import java.util.UUID;

/**
 * One permission a draft changed: the value it sets, live's value when it was set (its base), and what its
 * effect depends on in live (GrantDependencies).
 */
public record DraftGrantOverride(String principalType, UUID principalId, Permission permission, boolean value, boolean baseValue,
                                 GrantDependencies dependsOn) {}

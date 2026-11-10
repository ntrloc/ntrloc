package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.authorization.repository.Permission;

import java.util.UUID;

/** One permission a draft changed: the value it sets, and live's value when it was set (its base). */
public record DraftGrantOverride(String principalType, UUID principalId, Permission permission, boolean value, boolean baseValue) {}

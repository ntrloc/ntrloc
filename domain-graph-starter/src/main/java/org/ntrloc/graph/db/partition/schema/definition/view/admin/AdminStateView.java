package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

public record AdminStateView(UUID id, String name, @Nullable String description, boolean isInitial, @Nullable String entryProcessId, @Nullable String exitProcessId, List<AdminTransitionView> transitions) {
}

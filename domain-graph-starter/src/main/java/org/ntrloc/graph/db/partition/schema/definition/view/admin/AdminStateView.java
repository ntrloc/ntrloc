package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

// kind is "NORMAL", "START", or "END". START/END are the pseudostates every machine owns; the
// editor renders them by kind, not by their sentinel name.
public record AdminStateView(UUID id, String name, @Nullable String description, String kind, @Nullable String entryProcessId, @Nullable String exitProcessId, List<AdminTransitionView> transitions) {
}

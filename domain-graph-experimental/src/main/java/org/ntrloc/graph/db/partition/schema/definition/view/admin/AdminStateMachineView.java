package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

public record AdminStateMachineView(UUID id, String name, @Nullable String description, List<AdminStateView> states) {
}

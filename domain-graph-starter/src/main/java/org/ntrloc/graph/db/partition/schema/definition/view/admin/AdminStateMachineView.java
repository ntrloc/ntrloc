package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.springframework.lang.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

// layout: how its diagram is drawn (see SetStateMachineLayoutMutation), null until it's been arranged.
public record AdminStateMachineView(UUID id, String name, @Nullable String description, List<AdminStateView> states,
                                   @Nullable JsonNode layout) {
}

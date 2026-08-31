package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.ntrloc.graph.db.partition.schema.definition.view.SortableFieldView;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AdminItemDefinitionView(UUID id, String name, String description, List<TraitRefView> traits, List<AdminPropertyDefinitionView> properties, Map<String, List<AdminItemLinkPerspectiveView>> links, List<SortableFieldView> sortableFields, @Nullable List<AdminStateMachineView> stateMachines, @Nullable UUID supertypeId, boolean abstractType, @Nullable String displayLabelPattern) {
}

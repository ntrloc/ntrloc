package org.ntrloc.graph.schema.definition.view.admin;

import org.ntrloc.graph.schema.definition.view.SortableFieldView;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AdminItemDefinitionView(UUID id, UUID entityId, String name, String description, List<TraitRefView> traits, List<AdminPropertyDefinitionView> properties, Map<String, List<AdminItemLinkPerspectiveView>> links, List<SortableFieldView> sortableFields, List<AdminPropertyGroupView> groups) {
}

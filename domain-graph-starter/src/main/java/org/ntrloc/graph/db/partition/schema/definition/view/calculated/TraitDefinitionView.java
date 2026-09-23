package org.ntrloc.graph.db.partition.schema.definition.view.calculated;

import org.ntrloc.graph.db.partition.schema.definition.view.SortableFieldView;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TraitDefinitionView(UUID id, String name, String description, List<PropertyDefinitionView> properties, List<PropertyGroupDefinitionView> groups, Map<String, List<ItemLinkPerspectiveView>> links, List<SortableFieldView> sortableFields) {
}

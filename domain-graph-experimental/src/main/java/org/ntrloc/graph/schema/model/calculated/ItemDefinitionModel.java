package org.ntrloc.graph.schema.model.calculated;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ItemDefinitionModel(UUID id, String name, String description, List<PropertyDefinitionModel> properties, Map<String, List<ItemLinkPerspectiveModel>> links) {
}

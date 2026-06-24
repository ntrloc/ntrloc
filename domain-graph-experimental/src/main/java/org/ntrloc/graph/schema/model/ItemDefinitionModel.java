package org.ntrloc.graph.schema.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ItemDefinitionModel(UUID id, String name, List<PropertyDefinitionModel> properties, Map<String, List<ItemLinkPerspectiveModel>> links) {

}

package org.ntrloc.graph.schema.model.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AdminItemDefinitionModel(UUID id, String name, String description, List<AdminPropertyDefinitionModel> properties, Map<String, List<AdminItemLinkPerspectiveModel>> links) {
}

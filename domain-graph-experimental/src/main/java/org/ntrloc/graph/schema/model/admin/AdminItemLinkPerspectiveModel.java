package org.ntrloc.graph.schema.model.admin;

import java.util.List;

public record AdminItemLinkPerspectiveModel(String itemType, String description, Integer minCardinality, Integer maxCardinality, List<AdminPropertyDefinitionModel> properties) {
}

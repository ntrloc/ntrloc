package org.ntrloc.graph.schema.model.calculated;

import java.util.List;

public record ItemLinkPerspectiveModel(String itemType, String description, Integer minCardinality, Integer maxCardinality, List<PropertyDefinitionModel> properties) {
}

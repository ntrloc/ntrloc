package org.ntrloc.graph.schema.definition.view.calculated;

import java.util.List;

public record ItemLinkPerspectiveView(String itemType, String description, Integer minCardinality, Integer maxCardinality, List<PropertyDefinitionView> properties) {
}

package org.ntrloc.graph.schema.definition.view.admin;

import java.util.List;

public record AdminItemLinkPerspectiveView(String itemType, String description, Integer minCardinality, Integer maxCardinality, List<AdminPropertyDefinitionView> properties) {
}

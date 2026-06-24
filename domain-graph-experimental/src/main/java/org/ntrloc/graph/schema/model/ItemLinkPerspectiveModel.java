package org.ntrloc.graph.schema.model;

import java.util.List;

/**
 * Represents an item's perspective on a link with another item.
 */
public record ItemLinkPerspectiveModel(String itemType, Integer minCardinality, Integer maxCardinality, List<PropertyDefinitionModel> properties) {
}

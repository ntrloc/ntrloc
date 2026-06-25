package org.ntrloc.graph.schema.definition.view.admin;

import java.util.UUID;

public record AdminItemLinkPerspectiveView(UUID id, UUID linkId, String itemType, String description, Integer minCardinality, Integer maxCardinality) {
}

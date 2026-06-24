package org.ntrloc.graph.schema.definition;

import java.util.UUID;

public record ItemLinkPerspectiveDefinition(UUID itemDefinitionId, UUID linkId, String name, String description, Integer minCardinality, Integer maxCardinality) {
}

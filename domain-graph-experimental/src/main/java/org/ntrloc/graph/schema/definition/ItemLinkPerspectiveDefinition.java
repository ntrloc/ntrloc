package org.ntrloc.graph.schema.definition;

import java.util.UUID;

public record ItemLinkPerspectiveDefinition(UUID itemDefinitionId, UUID linkId, String name, Integer minCardinality, Integer maxCardinality) {
}

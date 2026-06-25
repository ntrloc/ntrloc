package org.ntrloc.graph.schema.definition.operation;

import java.util.UUID;

public record CreatePerspectiveOperation(UUID itemId, String name, String description, Integer minCardinality, Integer maxCardinality) {
}

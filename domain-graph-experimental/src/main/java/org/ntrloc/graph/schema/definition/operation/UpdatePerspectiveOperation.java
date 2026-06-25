package org.ntrloc.graph.schema.definition.operation;

import java.util.UUID;

public record UpdatePerspectiveOperation(UUID id, String name, String description, Integer minCardinality, Integer maxCardinality) implements SchemaOperation {
}

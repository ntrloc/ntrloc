package org.ntrloc.graph.schema.definition.operation;

import java.util.UUID;

public record UpdateItemOperation(UUID id, String name, String description) implements SchemaOperation {
}

package org.ntrloc.graph.schema.definition.operation;

import java.util.UUID;

public record DeleteItemOperation(UUID id) implements SchemaOperation {
}

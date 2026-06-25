package org.ntrloc.graph.schema.definition.operation;

import java.util.List;

public record CreateItemOperation(String name, String description, List<CreatePropertyOperation> properties) implements SchemaOperation {
}

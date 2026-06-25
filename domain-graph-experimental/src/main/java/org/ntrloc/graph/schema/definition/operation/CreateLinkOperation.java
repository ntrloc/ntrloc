package org.ntrloc.graph.schema.definition.operation;

import java.util.List;

public record CreateLinkOperation(List<CreatePropertyOperation> properties, List<CreatePerspectiveOperation> perspectives) implements SchemaOperation {
}

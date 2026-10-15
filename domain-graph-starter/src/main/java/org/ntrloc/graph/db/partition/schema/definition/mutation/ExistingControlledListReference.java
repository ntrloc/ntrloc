package org.ntrloc.graph.db.partition.schema.definition.mutation;

import java.util.UUID;

public record ExistingControlledListReference(UUID listId) implements ControlledListReference {
}

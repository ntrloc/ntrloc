package org.ntrloc.graph.db.partition.schema.definition.view.calculated;

import java.util.List;

public record SchemaView(List<ItemDefinitionView> items, List<TraitDefinitionView> traits) {
}

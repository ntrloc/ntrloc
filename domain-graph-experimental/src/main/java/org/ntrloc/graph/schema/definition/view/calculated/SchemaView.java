package org.ntrloc.graph.schema.definition.view.calculated;

import java.util.List;

public record SchemaView(List<ItemDefinitionView> items, List<TraitDefinitionView> traits) {
}

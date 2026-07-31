package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import java.util.List;
import java.util.UUID;

public record AdminLinkView(UUID id, List<AdminPropertyDefinitionView> properties) {
}

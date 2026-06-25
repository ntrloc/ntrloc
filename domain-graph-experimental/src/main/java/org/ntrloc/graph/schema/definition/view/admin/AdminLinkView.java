package org.ntrloc.graph.schema.definition.view.admin;

import java.util.List;
import java.util.UUID;

public record AdminLinkView(UUID id, List<AdminPropertyDefinitionView> properties) {
}

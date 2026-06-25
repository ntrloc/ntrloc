package org.ntrloc.graph.schema.definition.view.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AdminItemDefinitionView(UUID id, String name, String description, List<AdminPropertyDefinitionView> properties, Map<String, List<AdminItemLinkPerspectiveView>> links) {
}

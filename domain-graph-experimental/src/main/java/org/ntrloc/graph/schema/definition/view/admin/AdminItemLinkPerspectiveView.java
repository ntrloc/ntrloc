package org.ntrloc.graph.schema.definition.view.admin;

import org.ntrloc.graph.schema.definition.view.DefinedInView;
import org.ntrloc.graph.schema.definition.view.TargetEntityView;

import java.util.List;
import java.util.UUID;

public record AdminItemLinkPerspectiveView(UUID id, UUID linkId, List<TargetEntityView> targets, String description, Integer minCardinality, Integer maxCardinality, DefinedInView definedIn) {
}

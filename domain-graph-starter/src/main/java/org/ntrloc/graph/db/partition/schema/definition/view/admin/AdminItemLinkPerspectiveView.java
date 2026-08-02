package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;
import org.ntrloc.graph.db.partition.schema.definition.view.TargetEntityView;

import java.util.List;
import java.util.UUID;

public record AdminItemLinkPerspectiveView(UUID id, UUID linkId, List<TargetEntityView> targets, String description, Integer minCardinality, Integer maxCardinality, DefinedInView definedIn) {
}

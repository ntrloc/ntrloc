package org.ntrloc.graph.db.partition.schema.definition.view.calculated;

import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;
import org.ntrloc.graph.db.partition.schema.definition.view.TargetEntityView;

import java.util.List;

public record ItemLinkPerspectiveView(List<TargetEntityView> targets, String description, Integer minCardinality, Integer maxCardinality, List<PropertyDefinitionView> properties, DefinedInView definedIn) {
}

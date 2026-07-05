package org.ntrloc.graph.schema.definition.view.admin;

import org.ntrloc.graph.schema.definition.PropertyCardinality;
import org.ntrloc.graph.schema.definition.PropertyType;
import org.ntrloc.graph.schema.definition.PropertyUsage;
import org.ntrloc.graph.schema.definition.view.DefinedInView;

import java.util.UUID;

public record AdminPropertyDefinitionView(UUID id, String name, String description, PropertyType type, PropertyCardinality cardinality, PropertyUsage usage, DefinedInView definedIn, UUID controlledListId, UUID groupId) {
}

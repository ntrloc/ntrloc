package org.ntrloc.graph.schema.definition.view.admin;

import java.util.List;

public record AdminSchemaView(List<AdminItemDefinitionView> items, List<PropertyTypeView> propertyTypes) {
}

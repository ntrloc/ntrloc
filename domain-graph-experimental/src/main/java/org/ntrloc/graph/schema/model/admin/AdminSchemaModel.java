package org.ntrloc.graph.schema.model.admin;

import java.util.List;

public record AdminSchemaModel(List<AdminItemDefinitionModel> items, List<PropertyTypeModel> propertyTypes) {
}

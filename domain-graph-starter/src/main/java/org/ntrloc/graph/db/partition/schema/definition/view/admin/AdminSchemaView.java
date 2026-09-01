package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import java.util.List;

public record AdminSchemaView(List<AdminItemDefinitionView> items, List<AdminTraitDefinitionView> traits,
                             List<AdminLinkView> links, List<PropertyTypeView> propertyTypes,
                             List<AdminControlledListView> controlledLists) {
}

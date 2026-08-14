package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;

import java.util.List;
import java.util.UUID;

public record ObjectAdminPropertyDefinitionView(
        UUID id, String name, String description, PropertyType type, PropertyCardinality cardinality,
        PropertyUsage usage, DefinedInView definedIn, UUID controlledListId, boolean facetable,
        List<AdminPropertyDefinitionView> properties
) implements AdminPropertyDefinitionView {

    @Override
    public AdminPropertyDefinitionView withDefinedIn(DefinedInView definedIn) {
        return new ObjectAdminPropertyDefinitionView(id, name, description, type, cardinality, usage, definedIn, controlledListId, facetable, properties);
    }

    public ObjectAdminPropertyDefinitionView withProperties(List<AdminPropertyDefinitionView> properties) {
        return new ObjectAdminPropertyDefinitionView(id, name, description, type, cardinality, usage, definedIn, controlledListId, facetable, properties);
    }
}

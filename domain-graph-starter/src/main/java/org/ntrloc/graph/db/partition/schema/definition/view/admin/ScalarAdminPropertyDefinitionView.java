package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;

import java.util.UUID;

public record ScalarAdminPropertyDefinitionView(
        UUID id, String name, String description, PropertyType type, PropertyCardinality cardinality,
        PropertyUsage usage, DefinedInView definedIn, UUID controlledListId, boolean facetable
) implements AdminPropertyDefinitionView {

    @Override
    public AdminPropertyDefinitionView withDefinedIn(DefinedInView definedIn) {
        return new ScalarAdminPropertyDefinitionView(id, name, description, type, cardinality, usage, definedIn, controlledListId, facetable);
    }
}

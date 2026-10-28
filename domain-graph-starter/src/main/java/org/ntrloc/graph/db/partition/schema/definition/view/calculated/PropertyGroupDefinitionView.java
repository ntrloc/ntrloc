package org.ntrloc.graph.db.partition.schema.definition.view.calculated;

import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;

import java.util.List;
import java.util.UUID;

// Purely structural -- see AdminPropertyGroupView, this is its calculated (public schema) twin.
public record PropertyGroupDefinitionView(
        UUID id, String name, String description, DefinedInView definedIn,
        List<PropertyDefinitionView> properties, List<PropertyGroupDefinitionView> groups
) {

    public PropertyGroupDefinitionView withDefinedIn(DefinedInView definedIn) {
        return new PropertyGroupDefinitionView(id, name, description, definedIn, properties, groups);
    }
}

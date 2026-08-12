package org.ntrloc.graph.db.partition.schema.definition.view.calculated;

import org.ntrloc.graph.db.partition.schema.AllowedValue;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;

import java.util.List;
import java.util.UUID;

public record ObjectPropertyDefinitionView(
        UUID id, String name, String description, PropertyType type, PropertyCardinality cardinality,
        DefinedInView definedIn, List<AllowedValue> allowedValues,
        List<PropertyDefinitionView> properties
) implements PropertyDefinitionView {

    @Override
    public PropertyDefinitionView withDefinedIn(DefinedInView definedIn) {
        return new ObjectPropertyDefinitionView(id, name, description, type, cardinality, definedIn, allowedValues, properties);
    }
}

package org.ntrloc.graph.db.partition.schema.definition.view.calculated;

import org.ntrloc.graph.db.partition.schema.AllowedValue;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;

import java.util.List;
import java.util.UUID;

// A property is always a leaf carrying a value; PropertyGroupDefinitionView is the structural
// counterpart (a separate kind, not a variant of this one) -- see its admin twin,
// AdminPropertyDefinitionView.
public record PropertyDefinitionView(
        UUID id, String name, String description, PropertyType type, PropertyCardinality cardinality,
        DefinedInView definedIn, List<AllowedValue> allowedValues
) {

    public PropertyDefinitionView withDefinedIn(DefinedInView definedIn) {
        return new PropertyDefinitionView(id, name, description, type, cardinality, definedIn, allowedValues);
    }
}

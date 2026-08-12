package org.ntrloc.graph.db.partition.schema.definition.view.calculated;

import org.ntrloc.graph.db.partition.schema.AllowedValue;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;

import java.util.List;
import java.util.UUID;

// Sealed for the same reason as its admin counterpart, AdminPropertyDefinitionView -- only an
// OBJECT-typed property has a "properties" key in its JSON at all.
public sealed interface PropertyDefinitionView
        permits ScalarPropertyDefinitionView, ObjectPropertyDefinitionView {

    UUID id();
    String name();
    String description();
    PropertyType type();
    PropertyCardinality cardinality();
    DefinedInView definedIn();
    List<AllowedValue> allowedValues();

    PropertyDefinitionView withDefinedIn(DefinedInView definedIn);

    static PropertyDefinitionView of(UUID id, String name, String description, PropertyType type,
                                      PropertyCardinality cardinality, DefinedInView definedIn,
                                      List<AllowedValue> allowedValues, List<PropertyDefinitionView> properties) {
        return type == PropertyType.OBJECT
                ? new ObjectPropertyDefinitionView(id, name, description, type, cardinality, definedIn, allowedValues, properties)
                : new ScalarPropertyDefinitionView(id, name, description, type, cardinality, definedIn, allowedValues);
    }
}

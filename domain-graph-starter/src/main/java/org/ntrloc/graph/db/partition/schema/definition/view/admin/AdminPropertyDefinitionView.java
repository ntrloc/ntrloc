package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;

import java.util.List;
import java.util.UUID;

// Sealed so a scalar property's JSON never carries a pointless "properties" key -- only
// ObjectAdminPropertyDefinitionView has nested children, since only PropertyType.OBJECT can have
// any. Never deserialized from a request body (mutations flow through their own dedicated
// records), so no @JsonTypeInfo discriminator is needed -- Jackson serializes each element by its
// runtime type without one.
public sealed interface AdminPropertyDefinitionView
        permits ScalarAdminPropertyDefinitionView, ObjectAdminPropertyDefinitionView {

    UUID id();
    String name();
    String description();
    PropertyType type();
    PropertyCardinality cardinality();
    PropertyUsage usage();
    DefinedInView definedIn();
    UUID controlledListId();

    // Used by the trait/supertype inheritance-tagging walk in SchemaViewBuilder to copy a property
    // with only definedIn changed, regardless of which concrete variant it is.
    AdminPropertyDefinitionView withDefinedIn(DefinedInView definedIn);

    static AdminPropertyDefinitionView of(UUID id, String name, String description, PropertyType type,
                                           PropertyCardinality cardinality, PropertyUsage usage,
                                           DefinedInView definedIn, UUID controlledListId,
                                           List<AdminPropertyDefinitionView> properties) {
        return type == PropertyType.OBJECT
                ? new ObjectAdminPropertyDefinitionView(id, name, description, type, cardinality, usage, definedIn, controlledListId, properties)
                : new ScalarAdminPropertyDefinitionView(id, name, description, type, cardinality, usage, definedIn, controlledListId);
    }
}

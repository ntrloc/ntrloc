package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;

import java.util.UUID;

// A property is always a leaf that carries a value, and the only thing a grant can target.
// Structure is AdminPropertyGroupView's job -- a separate kind of schema node, deliberately not a
// variant of this one, so nothing that takes a property can ever be handed a group by accident.
public record AdminPropertyDefinitionView(
        UUID id, String name, String description, PropertyType type, PropertyCardinality cardinality,
        PropertyUsage usage, DefinedInView definedIn, UUID controlledListId,
        // Admin-declared opt-in, separate from (and gated by) structural eligibility -- see
        // RegisterPartitionManager.isTermsFacetable.
        boolean facetable
) {

    // Used by the trait/supertype inheritance-tagging walk in SchemaViewBuilder to copy a property
    // with only definedIn changed.
    public AdminPropertyDefinitionView withDefinedIn(DefinedInView definedIn) {
        return new AdminPropertyDefinitionView(id, name, description, type, cardinality, usage, definedIn, controlledListId, facetable);
    }
}

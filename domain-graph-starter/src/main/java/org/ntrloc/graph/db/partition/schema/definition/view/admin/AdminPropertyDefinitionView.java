package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;

import java.util.UUID;

// A property is always a leaf that carries a value, and the only thing a grant can target.
// Structure is AdminPropertyGroupView's job -- a separate kind of schema node, deliberately not a
// variant of this one, so nothing that takes a property can ever be handed a group by accident.
//
// Sealed with exactly two variants -- AdminScalarPropertyDefinitionView for everything ordinary, and
// AdminBinaryPropertyDefinitionView, which additionally carries dynamicFields (see
// docs/ntrloc-dynamic-properties-design-notes.md section 10). This is a variant of the *same*
// property hierarchy, not a wholly separate kind of schema node the way AdminPropertyGroupView had to
// become when PropertyType.OBJECT was retired -- appropriate because a BINARY property remains a
// genuine property (valued, grantable), unlike OBJECT, which had neither. withDefinedIn is declared
// here (not left to duplicate per call site) so the trait/supertype inheritance-tagging walk in
// SchemaContentResolver can copy either variant uniformly.
public sealed interface AdminPropertyDefinitionView permits AdminScalarPropertyDefinitionView, AdminBinaryPropertyDefinitionView {

    UUID id();

    String name();

    String description();

    PropertyType type();

    PropertyCardinality cardinality();

    PropertyUsage usage();

    DefinedInView definedIn();

    UUID controlledListId();

    // Admin-declared opt-in, separate from (and gated by) structural eligibility -- see
    // RegisterPartitionManager.isTermsFacetable.
    boolean facetable();

    AdminPropertyDefinitionView withDefinedIn(DefinedInView definedIn);
}

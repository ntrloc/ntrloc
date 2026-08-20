package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;

// Groups the five fields that together answer "what kind of property is this" -- as opposed to
// where it lives (definedIn), what values it's constrained to (controlledListId), or how the
// register treats it (facetable). Exists purely to keep AdminPropertyDefinitionView.of's own
// parameter list short; ScalarAdminPropertyDefinitionView/ObjectAdminPropertyDefinitionView still
// carry these as their own flat fields (and so still serialize flat), this is a construction-time
// grouping only.
public record PropertyIdentity(String name, String description, PropertyType type,
                                PropertyCardinality cardinality, PropertyUsage usage) {}

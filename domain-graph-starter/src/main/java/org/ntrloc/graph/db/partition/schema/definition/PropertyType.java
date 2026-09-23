package org.ntrloc.graph.db.partition.schema.definition;

import java.util.EnumSet;
import java.util.Set;

// Every type here carries a value -- a property is always a leaf. Structure (nesting, paths) is a
// PropertyGroup's job, a separate kind of schema node, not a type of property.
public enum PropertyType {
    STRING, INT, LONG, DOUBLE, DATE, DATETIME, BOOLEAN, BINARY;

    public Set<PropertyCardinality> validCardinalities() {
        return switch (this) {
            case BOOLEAN, BINARY -> EnumSet.of(PropertyCardinality.SINGLE);
            default -> EnumSet.allOf(PropertyCardinality.class);
        };
    }
}

package org.ntrloc.graph.schema.definition;

import java.util.EnumSet;
import java.util.Set;

public enum PropertyType {
    STRING, INT, LONG, DATE, DATETIME, BOOLEAN, BINARY, OBJECT;

    public Set<PropertyCardinality> validCardinalities() {
        return switch (this) {
            case BOOLEAN, BINARY, OBJECT -> EnumSet.of(PropertyCardinality.SINGLE);
            default -> EnumSet.allOf(PropertyCardinality.class);
        };
    }
}

package org.ntrloc.graph.schema.definition;

import java.util.UUID;

public record IdentifiedPropertyDefinition(UUID id, PropertyDefinition definition) {

    public String name() {
        return definition.name();
    }

    public PropertyType type() {
        return definition.type();
    }

    public PropertyCardinality cardinality() {
        return definition.cardinality();
    }

}

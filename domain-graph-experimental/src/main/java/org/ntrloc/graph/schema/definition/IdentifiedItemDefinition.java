package org.ntrloc.graph.schema.definition;

import java.util.UUID;

public record IdentifiedItemDefinition(UUID id, ItemDefinition definition) {

    public String name() {
        return definition.name();
    }

    public String description() {
        return definition.description();
    }

}

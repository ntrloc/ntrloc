package org.ntrloc.graph.schema.definition;

import java.util.UUID;

public record IdentifiedItemLinkPerspectiveDefinition(UUID id, ItemLinkPerspectiveDefinition definition) {

    public UUID itemDefinitionId() {
        return definition.itemDefinitionId();
    }

    public UUID linkId() {
        return definition.linkId();
    }

    public String name() {
        return definition.name();
    }

    public Integer minCardinality() {
        return definition.minCardinality();
    }

    public Integer maxCardinality() {
        return definition.maxCardinality();
    }

}

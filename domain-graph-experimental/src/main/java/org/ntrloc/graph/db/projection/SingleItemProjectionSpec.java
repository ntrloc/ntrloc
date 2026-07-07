package org.ntrloc.graph.db.projection;

import java.util.UUID;

public record SingleItemProjectionSpec(String itemTypeName, UUID itemId, Boolean groupProperties) implements ProjectionSpec {

    public SingleItemProjectionSpec(String itemTypeName, UUID itemId) {
        this(itemTypeName, itemId, null);
    }
}

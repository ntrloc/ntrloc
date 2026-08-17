package org.ntrloc.graph.db.projection;

import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.UUID;

public record SingleItemProjectionSpec(String itemTypeName, UUID itemId,
                                        @Nullable Map<String, LinkProjectionSpec> links) implements ProjectionSpec {

    public SingleItemProjectionSpec(String itemTypeName, UUID itemId) {
        this(itemTypeName, itemId, null);
    }
}

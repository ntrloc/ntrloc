package org.ntrloc.graph.projection;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ItemProjectionSpec(String itemTypeName, UUID itemId,
                                  @Nullable Map<String, LinkProjectionSpec> links,
                                  // See ProjectionSpec's own comment -- null returns every
                                  // property (unchanged); non-null, even empty, returns only
                                  // these.
                                  @Nullable List<String> properties,
                                  @Nullable Boolean includePermissions,
                                  @Nullable Boolean includeStates) implements ProjectionSpec {

    // Preserves the pre-existing 5-arg call shape (this was the canonical constructor before
    // properties was added) -- defaults to null (every property, unchanged).
    public ItemProjectionSpec(String itemTypeName, UUID itemId, @Nullable Map<String, LinkProjectionSpec> links,
            @Nullable Boolean includePermissions, @Nullable Boolean includeStates) {
        this(itemTypeName, itemId, links, null, includePermissions, includeStates);
    }

    public ItemProjectionSpec(String itemTypeName, UUID itemId, @Nullable Map<String, LinkProjectionSpec> links) {
        this(itemTypeName, itemId, links, null, null, null);
    }

    public ItemProjectionSpec(String itemTypeName, UUID itemId) {
        this(itemTypeName, itemId, null, null, null, null);
    }
}

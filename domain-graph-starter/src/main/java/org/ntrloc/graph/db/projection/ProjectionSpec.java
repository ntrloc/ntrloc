package org.ntrloc.graph.db.projection;

import org.springframework.lang.Nullable;

import java.util.Map;

public sealed interface ProjectionSpec permits SingleItemProjectionSpec, CollectionProjectionSpec {

    String itemTypeName();

    @Nullable Map<String, LinkProjectionSpec> links();
}

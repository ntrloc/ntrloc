package org.ntrloc.graph.db.projection;

public sealed interface ProjectionSpec permits SingleItemProjectionSpec, CollectionProjectionSpec {

    String itemTypeName();
}

package org.ntrloc.graph.db.projection;

import java.util.UUID;

public record SingleItemProjectionSpec(String itemTypeName, UUID itemId) implements ProjectionSpec {}

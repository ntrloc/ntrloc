package org.ntrloc.graph.db.mutation;

import java.util.UUID;

public record LinkEndpointReference(UUID perspectiveId, ItemReference item) {
}

package org.ntrloc.graph.db.mutation;

import java.util.UUID;

public record LinkDeleteMutation(UUID linkId) implements LinkMutation {
}

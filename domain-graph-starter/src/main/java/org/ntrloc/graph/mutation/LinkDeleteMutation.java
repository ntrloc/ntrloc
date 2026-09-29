package org.ntrloc.graph.mutation;

import java.util.UUID;

public record LinkDeleteMutation(UUID linkId) implements LinkMutation {
}

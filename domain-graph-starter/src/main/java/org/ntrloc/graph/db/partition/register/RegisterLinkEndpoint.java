package org.ntrloc.graph.db.partition.register;

import java.util.UUID;

// Register's own endpoint shape, kept independent of ledger.LinkEndpoint per the partition
// isolation invariant (register must never import from the ledger package).
public record RegisterLinkEndpoint(UUID perspectiveId, UUID itemId) {
}

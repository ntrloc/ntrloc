package org.ntrloc.graph.db.partition.register;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writing the register: staging at prepare, committing or aborting, deletes, and marker postings. Only the
 * ledger/register coordinator holds this: it keeps the register consistent with the ledger
 * (docs/ntrloc-architecture-rationalization.md §2). See RegisterStore for each method.
 */
public interface RegisterStoreWriter {

    UUID stageItemCreate(UUID itemId, UUID itemTypeId, Map<UUID, Object> properties, Map<UUID, UUID> initialStates,
                         Set<UUID> binaryPropertyIds, StagingContext staging);

    UUID stageItemChange(UUID itemId, Map<UUID, Object> propertiesDiff, Map<UUID, UUID> stateChanges,
                         Set<UUID> stateMachinesEnded, Set<UUID> binaryPropertyIds, StagingContext staging);

    UUID stageLinkCreate(UUID linkId, UUID linkTypeId, RegisterLinkEndpoint endpointA, RegisterLinkEndpoint endpointB,
                         Map<UUID, Object> properties, StagingContext staging);

    UUID stageLinkUpdate(UUID linkId, Map<UUID, Object> propertiesDiff, StagingContext staging);

    BinaryValueChange commitItem(UUID itemId, UUID transactionId, UUID commitId);

    void commitLink(UUID linkId, UUID transactionId, UUID commitId);

    BinaryValueChange deleteItem(UUID itemId);

    void deleteLink(UUID linkId);

    void postItemMarkerAdd(UUID itemId, UUID markerId);

    void postItemMarkerRemove(UUID itemId, UUID markerId);

    void abort(UUID transactionId);
}
